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
package org.linagora.linshare.core.batches.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.RotationOutcome;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.exception.BatchBusinessException;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.job.quartz.ResultContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;

class RotateKekBatchImplTest {

	@SuppressWarnings("unchecked")
	private final AccountRepository<Account> accountRepository = mock(AccountRepository.class);

	private final DocumentRepository documentRepository = mock(DocumentRepository.class);

	private Document documentWithBucket(String uuid) {
		Document document = new Document();
		document.setUuid(uuid);
		document.setBucketUuid("bucket-1");
		// FileMetaData(kind, Document) unboxes these; the no-arg Document()
		// constructor leaves them null, unlike Document's other constructors.
		document.setHasThumbnail(false);
		document.setSize(5L);
		return document;
	}

	@Test
	void needToRunIsFalseWhenStoreIsNotEncrypted() {
		FileDataStore fileDataStore = mock(FileDataStore.class);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		assertEquals(false, batch.needToRun());
		assertEquals(Collections.emptyList(), batch.getAll(mock(BatchRunContext.class)));
	}

	@Test
	void needToRunIsFalseWhenEncryptedButRotationNotConfigured() {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.isRotationConfigured()).thenReturn(false);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		assertEquals(false, batch.needToRun());
	}

	@Test
	void needToRunIsTrueWhenEncryptedAndRotationConfigured() {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.isRotationConfigured()).thenReturn(true);
		when(documentRepository.findAllIdentifiers()).thenReturn(List.of("doc-1"));
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		assertTrue(batch.needToRun());
		assertEquals(List.of("doc-1"), batch.getAll(mock(BatchRunContext.class)));
	}

	@Test
	void executeMarksProcessedWhenOutcomeIsRotated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.isRotationConfigured()).thenReturn(true);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ROTATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeMarksUnprocessedWhenOutcomeIsAlreadyRotated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ALREADY_ROTATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(false, context.getProcessed());
	}

	@Test
	void executeSkipsDocumentsWithoutABucketUuid() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		Document document = new Document();
		document.setUuid("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(false, context.getProcessed());
	}

	@Test
	void executeWrapsIOExceptionAsBatchBusinessException() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenThrow(new IOException("boom"));
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		assertThrows(BatchBusinessException.class,
				() -> batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0));
	}
}
