/*
 * Copyright (C) 2007-2023 - LINAGORA
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.linagora.linshare.core.dao.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.linagora.linshare.core.dao.AtomicBlobReplace;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.exception.TechnicalErrorCode;
import org.linagora.linshare.core.exception.TechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;

import com.google.common.io.ByteSource;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;

public class MongoFileDataStoreImpl implements FileDataStore, AtomicBlobReplace {

	private static final Logger logger = LoggerFactory.getLogger(MongoFileDataStoreImpl.class);

	private static final String GENERATION_KEY = "generation";

	private GridFsOperations gridOperations;

	private SimpleMongoClientDatabaseFactory mongoDbFactory;

	public MongoFileDataStoreImpl(GridFsOperations gridOperations,
			SimpleMongoClientDatabaseFactory mongoDbFactory) {
		super();
		this.gridOperations = gridOperations;
		this.mongoDbFactory = mongoDbFactory;
	}

	/** Removes every copy stored under this uuid, including any stray duplicate. */
	@Override
	public void remove(FileMetaData metadata) {
		gridOperations.delete(uuidQuery(metadata.getUuid()));
	}

	@Override
	public FileMetaData add(ByteSource byteSource, FileMetaData metadata) throws IOException {
		DBObject meta = new BasicDBObject();
		// It is not used/useful for mongo.
		// meta.put("bucketUuid", metadata.getBucketUuid());
		if (metadata.getUuid() == null) {
			metadata.setUuid(UUID.randomUUID().toString());
		}
		meta.put("uuid", metadata.getUuid());
		// Mongo does not support empty file name.
		if (metadata.getFileName() == null) {
			metadata.setFileName(UUID.randomUUID().toString());
		}
		// this throws MongoException
		gridOperations.store(byteSource.openBufferedStream(), metadata.getFileName(), metadata.getMimeType(), meta);
		return metadata;
	}

	@Override
	public ByteSource get(FileMetaData metadata) {
		if (resolveNewest(metadata.getUuid()) == null) {
			logger.error("Can not find document '{}' in gridfs", metadata.getUuid());
			throw new TechnicalException(TechnicalErrorCode.GENERIC,
					"Can not find document in gridfs : " + metadata.getUuid());
		}
		return new ByteSource() {
			@Override
			public InputStream openStream() throws IOException {
				// Resolved again at open time, not captured above: the blob may
				// have been atomically replaced in between.
				GridFSFile file = resolveNewest(metadata.getUuid());
				if (file == null) {
					throw new IOException("no such blob in gridfs: " + metadata.getUuid());
				}
				GridFSDownloadStream gridFSDownloadStream = getGridFs().openDownloadStream(file.getObjectId());
				GridFsResource gridFsResource = new GridFsResource(file, gridFSDownloadStream);
				return gridFsResource.getInputStream();
			}
		};
	}

	/**
	 * Replacement order: higher {@code metadata.generation} first (set by
	 * {@link #atomicReplace}, so it doesn't depend on the clocks of the
	 * nodes involved), then newest upload, then newest {@code _id}. Files
	 * written by {@link #add} carry no generation and count as 0.
	 */
	private static final Comparator<GridFSFile> NEWEST_FIRST = Comparator
			.<GridFSFile>comparingLong(MongoFileDataStoreImpl::generationOf)
			.thenComparing(GridFSFile::getUploadDate, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(GridFSFile::getObjectId, Comparator.nullsFirst(Comparator.naturalOrder()))
			.reversed();

	private static long generationOf(GridFSFile file) {
		org.bson.Document metadata = file.getMetadata();
		Object generation = metadata == null ? null : metadata.get(GENERATION_KEY);
		return generation instanceof Number ? ((Number) generation).longValue() : 0L;
	}

	/** Every copy stored under this uuid, newest first. */
	private List<GridFSFile> findNewestFirst(String uuid) {
		List<GridFSFile> files = new ArrayList<>();
		GridFSFindIterable find = gridOperations.find(uuidQuery(uuid));
		if (find != null) {
			for (GridFSFile file : find) {
				files.add(file);
			}
		}
		files.sort(NEWEST_FIRST);
		return files;
	}

	/**
	 * The copy that currently represents this uuid, or null if there is none.
	 *
	 * <p>Normally there is exactly one. Two can exist if {@link #atomicReplace}
	 * was interrupted after storing the replacement but before deleting what
	 * it replaced; the replacement is the newest by construction, and it is
	 * only ever stored from an already-verified source, so it is the right
	 * one. The stale copies are deleted here (best-effort) rather than
	 * making every later read of this uuid fail.
	 */
	private GridFSFile resolveNewest(String uuid) {
		List<GridFSFile> files = findNewestFirst(uuid);
		if (files.isEmpty()) {
			return null;
		}
		if (files.size() > 1) {
			List<ObjectId> staleIds = new ArrayList<>();
			for (GridFSFile stale : files.subList(1, files.size())) {
				staleIds.add(stale.getObjectId());
			}
			logger.warn("{} copies of document '{}' found in gridfs (interrupted replace?); keeping the newest "
					+ "and deleting the {} older one(s).", files.size(), uuid, staleIds.size());
			try {
				gridOperations.delete(new Query().addCriteria(Criteria.where("_id").in(staleIds)));
			} catch (RuntimeException e) {
				logger.warn("Could not delete the stale copies of document '{}' in gridfs: {}", uuid,
						e.getMessage());
			}
		}
		return files.get(0);
	}

	@Override
	public boolean exists(FileMetaData metadata) {
		Query query = new Query().addCriteria(Criteria.where("metadata.uuid").is(metadata.getUuid()));
		GridFSFile find = gridOperations.findOne(query);
		if (find == null) {
			return false;
		}
		return true;
	}

	private GridFSBucket getGridFs() {
		MongoDatabase db = mongoDbFactory.getMongoDatabase();
		return GridFSBuckets.create(db);
	}

	private Query uuidQuery(String uuid) {
		return new Query().addCriteria(Criteria.where("metadata.uuid").is(uuid));
	}

	/**
	 * GridFS has no native "replace contents at an existing key" primitive:
	 * add() always inserts a new document, it never overwrites by uuid. So
	 * the new document is inserted under target's uuid first, and only the
	 * document(s) previously holding that uuid (captured beforehand, so the
	 * one just inserted is never touched) are removed afterward — a reader
	 * of target's uuid always finds at least one valid document, never zero.
	 *
	 * <p>Not atomic against a crash between those steps, so it is made
	 * safe to interrupt instead: the new document carries a higher
	 * {@code generation} than anything it replaces, and
	 * {@link #resolveNewest} resolves duplicates of a uuid to the newest and
	 * deletes the rest. An interrupted replace therefore leaves, at worst, a
	 * stale copy that is ignored and cleaned up on the next access, and a
	 * re-run (the source is only deleted last) simply finds its work done.
	 */
	@Override
	public void atomicReplace(FileMetaData source, FileMetaData target) throws IOException {
		GridFSFile sourceFile = resolveNewest(source.getUuid());
		if (sourceFile == null) {
			throw new IOException("no such source blob: " + source.getUuid());
		}

		List<GridFSFile> targetFiles = findNewestFirst(target.getUuid());
		List<ObjectId> staleTargetIds = new ArrayList<>();
		for (GridFSFile file : targetFiles) {
			staleTargetIds.add(file.getObjectId());
		}
		long generation = targetFiles.isEmpty() ? 0L : generationOf(targetFiles.get(0)) + 1;

		DBObject meta = new BasicDBObject();
		meta.put("uuid", target.getUuid());
		meta.put(GENERATION_KEY, generation);
		try (GridFSDownloadStream in = getGridFs().openDownloadStream(sourceFile.getObjectId())) {
			gridOperations.store(in, fileNameFor(target, targetFiles, sourceFile), target.getMimeType(), meta);
		}

		if (!staleTargetIds.isEmpty()) {
			gridOperations.delete(new Query().addCriteria(Criteria.where("_id").in(staleTargetIds)));
		}
		gridOperations.delete(uuidQuery(source.getUuid()));
	}

	/**
	 * Mongo does not support an empty file name (see {@link #add}), and the
	 * batches build target metadata from the Document, which carries none.
	 * Keep whatever name the blob being replaced already had.
	 */
	private String fileNameFor(FileMetaData target, List<GridFSFile> replaced, GridFSFile source) {
		if (target.getFileName() != null && !target.getFileName().isEmpty()) {
			return target.getFileName();
		}
		for (GridFSFile file : replaced) {
			if (file.getFilename() != null && !file.getFilename().isEmpty()) {
				return file.getFilename();
			}
		}
		if (source.getFilename() != null && !source.getFilename().isEmpty()) {
			return source.getFilename();
		}
		return UUID.randomUUID().toString();
	}
}
