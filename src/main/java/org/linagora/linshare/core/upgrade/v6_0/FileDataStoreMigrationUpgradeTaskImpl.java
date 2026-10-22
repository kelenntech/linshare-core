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
package org.linagora.linshare.core.upgrade.v6_0;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.linagora.linshare.core.batches.impl.GenericUpgradeTaskImpl;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.MigrationFileDataStoreImpl;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.constants.ThumbnailType;
import org.linagora.linshare.core.domain.constants.UpgradeTaskType;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.entities.Thumbnail;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.exception.BatchBusinessException;
import org.linagora.linshare.core.exception.BusinessException;
import org.linagora.linshare.core.exception.TechnicalException;
import org.linagora.linshare.core.job.quartz.BatchResultContext;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.job.quartz.ResultContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;
import org.linagora.linshare.mongo.repository.UpgradeTaskLogMongoRepository;
import org.linagora.linshare.storage.encryption.exception.EncryptedBlobException;
import org.linagora.linshare.storage.encryption.format.ChunkLayout;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobFormat;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;

import com.google.common.io.ByteSource;

/**
 * Copies documents from the old (GridFS) store to the new (jcloud) one under
 * the migration-gridfs-to-jcloud profile. Must be given the raw store
 * ({@code rawFileDataStore}), never the encryption decorator: blobs are
 * copied exactly as stored, so encrypted ones move as ciphertext (their
 * authenticated data is bound to the uuid, which doesn't change) and legacy
 * ones as plaintext.
 */
public class FileDataStoreMigrationUpgradeTaskImpl extends GenericUpgradeTaskImpl {

	protected DocumentRepository repository;

	protected FileDataStore fileDataStore;

	public FileDataStoreMigrationUpgradeTaskImpl(
			AccountRepository<Account> accountRepository,
			UpgradeTaskLogMongoRepository upgradeTaskLogMongoRepository,
			DocumentRepository repository,
			FileDataStore fileDataStore) {
		super(accountRepository, upgradeTaskLogMongoRepository);
		this.repository = repository;
		this.fileDataStore = fileDataStore;
	}

	@Override
	public UpgradeTaskType getUpgradeTaskType() {
		return UpgradeTaskType.OPTIONAL_MIGRATE_FILE_DATA_STORAGE_TO_A_NEW_ONE;
	}

	@Override
	public List<String> getAll(BatchRunContext batchRunContext) {
		List<String> documents = repository.findAllDocumentsToUpgrade();
		return documents;
	}

	@Override
	public ResultContext execute(BatchRunContext batchRunContext, String identifier, long total, long position)
			throws BatchBusinessException, BusinessException {
		if (!(fileDataStore instanceof MigrationFileDataStoreImpl)) {
			console.logError(batchRunContext, total, position, "Wrong fileDatastore ! Check spring profiles.");
			throw new TechnicalException("Wrong fileDatastore ! Check spring profiles.");
		}
		Document document = repository.findByUuid(identifier);
		if (document == null) {
			return null;
		}
		BatchResultContext<Document> res = new BatchResultContext<Document>(document);
		res.setProcessed(false);
		console.logDebug(batchRunContext, total, position, "Processing document : " + document.toString());

		try {
			FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, document);
			ByteSource byteSource = fileDataStore.get(metadata);
			metadata = fileDataStore.add(byteSource, withStoredSize(metadata, document.getSize()));
			document.setBucketUuid(metadata.getBucketUuid());
			if (document.getHasThumbnail()) {
				console.logDebug(batchRunContext, total, position, "document has thumbnail.");
				Map<ThumbnailType, Thumbnail> thumbnails = document.getThumbnails();
				Set<ThumbnailType> thumbnailTypes = thumbnails.keySet();
				for (ThumbnailType thumbnailType : thumbnailTypes) {
					console.logDebug(batchRunContext, total, position, "thumbnailType: " + thumbnailType);
					FileMetaDataKind metaDataKind = ThumbnailType.toFileMetaDataKind(thumbnailType);
					console.logDebug(batchRunContext, total, position, "metaDataKind: " + metaDataKind);
					FileMetaData metadataTmb = new FileMetaData(metaDataKind, document);
					ByteSource byteSourceTmb = fileDataStore.get(metadataTmb);
					metadata = fileDataStore.add(byteSourceTmb, metadataTmb);
				}
			}
		} catch (IOException e) {
			String msg = String.format("Can not copy the current document to the new file data store : %1$s.", document.toString());
			console.logError(batchRunContext, msg);
			logger.error(e.getMessage(), e);
			return res;
		}
		document.setToUpgrade(false);
		repository.update(document);
		res.setProcessed(true);
		return res;
	}

	/**
	 * jcloud sends the metadata size as the upload's Content-Length, but a
	 * Document's size is its plaintext size: an encrypted blob is stored
	 * larger than that (header plus per-chunk tags), so its stored size is
	 * read from its header instead.
	 */
	private FileMetaData withStoredSize(FileMetaData metadata, long plaintextSize) throws IOException {
		FileMetaData stored = new FileMetaData(metadata.getKind(), metadata.getMimeType(),
				storedSizeOf(metadata, plaintextSize), metadata.getFileName());
		stored.setUuid(metadata.getUuid());
		stored.setBucketUuid(metadata.getBucketUuid());
		return stored;
	}

	private long storedSizeOf(FileMetaData metadata, long plaintextSize) throws IOException {
		byte[] head;
		try (InputStream in = fileDataStore.getRange(metadata, 0, EncryptedBlobHeader.MAX_HEADER_LENGTH)
				.openStream()) {
			head = in.readNBytes(EncryptedBlobHeader.MAX_HEADER_LENGTH);
		}
		byte[] magic = EncryptedBlobHeader.magic();
		if (head.length < magic.length || !Arrays.equals(Arrays.copyOf(head, magic.length), magic)) {
			return plaintextSize;
		}
		try {
			return ChunkLayout.of(EncryptedBlobFormat.readHeader(new ByteArrayInputStream(head)))
					.totalPhysicalLength();
		} catch (EncryptedBlobException e) {
			// Checked, so this document is reported and skipped rather than
			// the runtime exception stopping the whole upgrade task.
			throw new IOException("Unreadable encryption header for document " + metadata.getUuid() + ": "
					+ e.getMessage(), e);
		}
	}

	@Override
	public void notify(BatchRunContext batchRunContext, ResultContext context, long total, long position) {
		@SuppressWarnings("unchecked")
		BatchResultContext<Document> res = (BatchResultContext<Document>) context;
		Document resource = res.getResource();
		if (res.getProcessed()) {
			logInfo(batchRunContext, total, position, resource + " has been updated.");
		} else {
			logInfo(batchRunContext, total, position, resource + " has been skipped.");
		}
	}

	@Override
	public void notifyError(BatchBusinessException exception, String identifier, long total, long position,
			BatchRunContext batchRunContext) {
		@SuppressWarnings("unchecked")
		BatchResultContext<Document> res = (BatchResultContext<Document>) exception.getContext();
		Document resource = res.getResource();
		console.logError(batchRunContext, total, position, "The upgrade task : " + resource.toString() + " failed.");
		logger.error("Error occured while updating the document : "
				+ resource +
				". BatchBusinessException", exception);
	}
}
