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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import org.bson.BsonObjectId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.mockito.MockedStatic;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;

/**
 * Unit-level coverage for {@link MongoFileDataStoreImpl#atomicReplace}: with
 * {@link GridFsOperations} mocked, this locks in the ordering that avoids the
 * reported corruption (docs/ARCH.md N/A — see the atomicReplace javadoc) —
 * insert the replacement under target's uuid, capture the *pre-existing*
 * target document(s) before that insert, and only then delete exactly those
 * plus the source, never the document just inserted. It does not exercise
 * real GridFS wire behavior (this repo's embedded Mongo test tool explicitly
 * does not support GridFS); that was verified manually against a real
 * dockerized MongoDB during development of this fix.
 */
class MongoFileDataStoreImplTest {

	private GridFsOperations gridOperations;

	private MongoFileDataStoreImpl store;

	private MockedStatic<GridFSBuckets> gridFSBuckets;

	private GridFSBucket bucket;

	private void setUp() {
		gridOperations = mock(GridFsOperations.class);
		SimpleMongoClientDatabaseFactory mongoDbFactory = mock(SimpleMongoClientDatabaseFactory.class);
		MongoDatabase database = mock(MongoDatabase.class);
		when(mongoDbFactory.getMongoDatabase()).thenReturn(database);
		bucket = mock(GridFSBucket.class);
		gridFSBuckets = mockStatic(GridFSBuckets.class);
		gridFSBuckets.when(() -> GridFSBuckets.create(database)).thenReturn(bucket);
		store = new MongoFileDataStoreImpl(gridOperations, mongoDbFactory);
	}

	@AfterEach
	void tearDown() {
		if (gridFSBuckets != null) {
			gridFSBuckets.close();
		}
	}

	private FileMetaData metadata(String uuid, String fileName) {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "text/plain", 5L, fileName);
		metadata.setUuid(uuid);
		return metadata;
	}

	private GridFSFile gridFsFile(ObjectId id) {
		return new GridFSFile(new BsonObjectId(id), "f.bin", 5L, 261120, new Date(), new org.bson.Document());
	}

	private GridFSFindIterable findIterableOf(GridFSFile... files) {
		GridFSFindIterable iterable = mock(GridFSFindIterable.class);
		when(iterable.iterator()).thenAnswer(inv -> cursorOf(files));
		when(iterable.first()).thenReturn(files.length > 0 ? files[0] : null);
		return iterable;
	}

	@SuppressWarnings("unchecked")
	private MongoCursor<GridFSFile> cursorOf(GridFSFile... files) {
		java.util.Iterator<GridFSFile> backing = List.of(files).iterator();
		MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
		when(cursor.hasNext()).thenAnswer(inv -> backing.hasNext());
		when(cursor.next()).thenAnswer(inv -> backing.next());
		return cursor;
	}

	// Mockito probes argThat predicates with a null argument while a stub is
	// being registered (not just when the real call is matched later), so
	// these must tolerate a null query rather than assume a real invocation.
	private boolean queryTargetsUuid(Query query, String uuid) {
		if (query == null) {
			return false;
		}
		Object value = query.getQueryObject().get("metadata.uuid");
		return uuid.equals(value);
	}

	@SuppressWarnings("unchecked")
	private boolean queryDeletesIds(Query query, ObjectId... ids) {
		if (query == null) {
			return false;
		}
		Object idCriteria = query.getQueryObject().get("_id");
		if (!(idCriteria instanceof org.bson.Document)) {
			return false;
		}
		Object in = ((org.bson.Document) idCriteria).get("$in");
		return in instanceof List && ((List<ObjectId>) in).size() == ids.length
				&& ((List<ObjectId>) in).containsAll(List.of(ids));
	}

	@Test
	void movesBytesFromSourceToTargetWhenNoPriorTargetExists() throws IOException {
		setUp();
		ObjectId sourceId = new ObjectId();
		GridFSFile sourceFile = gridFsFile(sourceId);
		FileMetaData source = metadata("source-uuid", "source.bin");
		FileMetaData target = metadata("target-uuid", "target.bin");

		GridFSFindIterable sourceIterable = findIterableOf(sourceFile);
		GridFSFindIterable emptyTargetIterable = findIterableOf();
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "source-uuid")))).thenReturn(sourceIterable);
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "target-uuid")))).thenReturn(emptyTargetIterable);
		GridFSDownloadStream downloadStream = mock(GridFSDownloadStream.class);
		when(bucket.openDownloadStream(sourceId)).thenReturn(downloadStream);

		store.atomicReplace(source, target);

		verify(gridOperations).store(eq(downloadStream), eq("target.bin"), eq("text/plain"),
				argThat((com.mongodb.DBObject meta) -> "target-uuid".equals(meta.get("uuid"))));
		verify(gridOperations).delete(argThat(q -> queryTargetsUuid(q, "source-uuid")));
		// No pre-existing target document, so no id-based delete should happen.
		verify(gridOperations, never()).delete(argThat(q -> q != null && q.getQueryObject().containsKey("_id")));
	}

	@Test
	void deletesOnlyThePreExistingTargetDocumentNeverTheNewlyInsertedOne() throws IOException {
		setUp();
		ObjectId sourceId = new ObjectId();
		ObjectId staleTargetId = new ObjectId();
		GridFSFile sourceFile = gridFsFile(sourceId);
		GridFSFile staleTargetFile = gridFsFile(staleTargetId);
		FileMetaData source = metadata("source-uuid", "source.bin");
		FileMetaData target = metadata("target-uuid", "target.bin");

		GridFSFindIterable sourceIterable = findIterableOf(sourceFile);
		GridFSFindIterable staleTargetIterable = findIterableOf(staleTargetFile);
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "source-uuid")))).thenReturn(sourceIterable);
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "target-uuid")))).thenReturn(staleTargetIterable);
		GridFSDownloadStream downloadStream = mock(GridFSDownloadStream.class);
		when(bucket.openDownloadStream(sourceId)).thenReturn(downloadStream);

		store.atomicReplace(source, target);

		// Order matters for correctness: the stale target must be captured
		// and only deleted AFTER the new document is safely stored, and the
		// delete must target the captured stale id, never a fresh lookup by
		// uuid (which would also match the document just inserted).
		org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(gridOperations);
		inOrder.verify(gridOperations).store(any(), any(), any(), any(com.mongodb.DBObject.class));
		inOrder.verify(gridOperations).delete(argThat(q -> queryDeletesIds(q, staleTargetId)));
		inOrder.verify(gridOperations).delete(argThat(q -> queryTargetsUuid(q, "source-uuid")));
	}

	@Test
	void throwsWhenSourceDoesNotExist() {
		setUp();
		FileMetaData source = metadata("missing-uuid", "source.bin");
		FileMetaData target = metadata("target-uuid", "target.bin");
		GridFSFindIterable emptyIterable = findIterableOf();
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "missing-uuid")))).thenReturn(emptyIterable);

		assertThrows(IOException.class, () -> store.atomicReplace(source, target));
	}

	@Test
	void throwsWhenSourceIsAmbiguous() {
		setUp();
		FileMetaData source = metadata("dup-uuid", "source.bin");
		FileMetaData target = metadata("target-uuid", "target.bin");
		GridFSFindIterable ambiguousIterable = findIterableOf(gridFsFile(new ObjectId()), gridFsFile(new ObjectId()));
		when(gridOperations.find(argThat(q -> queryTargetsUuid(q, "dup-uuid")))).thenReturn(ambiguousIterable);

		assertThrows(org.linagora.linshare.core.exception.TechnicalException.class,
				() -> store.atomicReplace(source, target));
	}
}
