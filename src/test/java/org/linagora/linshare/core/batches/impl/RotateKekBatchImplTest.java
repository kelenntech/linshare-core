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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.linagora.linshare.core.batches.utils.BatchConsole;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.RotationOutcome;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.constants.ThumbnailType;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.entities.Thumbnail;
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
	void executeLogsWarnWhenBlobIsMissing() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.MISSING);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void executeLogsWarnWhenWrappedKeyIsTooLarge() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.WRAPPED_KEY_TOO_LARGE);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void executeLogsWarnWhenVerificationFails() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.VERIFICATION_FAILED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void executeDoesNotLogWarnWhenAlreadyRotated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ALREADY_ROTATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console, never()).logWarn(any(BatchRunContext.class), anyLong(), anyLong(), any(), any());
	}

	@Test
	void executeDoesNotLogWarnWhenRotated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ROTATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console, never()).logWarn(any(BatchRunContext.class), anyLong(), anyLong(), any(), any());
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

	private Document documentWithThumbnails(String uuid, ThumbnailType... types) {
		Document document = documentWithBucket(uuid);
		document.setHasThumbnail(true);
		Map<ThumbnailType, Thumbnail> thumbnails = new EnumMap<>(ThumbnailType.class);
		for (ThumbnailType type : types) {
			thumbnails.put(type, new Thumbnail(uuid + "-thmb-" + type.name().toLowerCase(), type, document));
		}
		document.setThumbnails(thumbnails);
		return document;
	}

	@Test
	void executeRotatesTheDocumentsThumbnailsAsWellAsItsMainBlob() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ROTATED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL, ThumbnailType.MEDIUM,
				ThumbnailType.LARGE, ThumbnailType.PDF);
		document.setThmbUuid("doc-1-legacy-thmb");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		ArgumentCaptor<FileMetaData> rotated = ArgumentCaptor.forClass(FileMetaData.class);
		verify(fileDataStore, org.mockito.Mockito.times(6)).rotateKek(rotated.capture());
		Map<FileMetaDataKind, String> uuidByKind = new EnumMap<>(FileMetaDataKind.class);
		for (FileMetaData metadata : rotated.getAllValues()) {
			uuidByKind.put(metadata.getKind(), metadata.getUuid());
			assertEquals("bucket-1", metadata.getBucketUuid());
		}
		assertEquals("doc-1", uuidByKind.get(FileMetaDataKind.DATA));
		assertEquals("doc-1-thmb-small", uuidByKind.get(FileMetaDataKind.THUMBNAIL_SMALL));
		assertEquals("doc-1-thmb-medium", uuidByKind.get(FileMetaDataKind.THUMBNAIL_MEDIUM));
		assertEquals("doc-1-thmb-large", uuidByKind.get(FileMetaDataKind.THUMBNAIL_LARGE));
		assertEquals("doc-1-thmb-pdf", uuidByKind.get(FileMetaDataKind.THUMBNAIL_PDF));
		assertEquals("doc-1-legacy-thmb", uuidByKind.get(FileMetaDataKind.THUMBNAIL));
		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeToleratesAThumbnailTypeTheDocumentDoesNotHave() throws Exception {
		// hasThumbnail is true but e.g. PDF generation is disabled: the main
		// FileMetaData(kind, Document) constructor would NPE on the absent type.
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.ALREADY_ROTATED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(fileDataStore, org.mockito.Mockito.times(2)).rotateKek(any(FileMetaData.class));
	}

	@Test
	void executeIsProcessedWhenOnlyAThumbnailNeededRotating() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenAnswer(invocation -> {
			FileMetaData metadata = invocation.getArgument(0);
			return metadata.getKind() == FileMetaDataKind.DATA ? RotationOutcome.ALREADY_ROTATED
					: RotationOutcome.ROTATED;
		});
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeLogsWarnForAMissingThumbnailBlobWithoutFailingTheDocument() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenAnswer(invocation -> {
			FileMetaData metadata = invocation.getArgument(0);
			return metadata.getKind() == FileMetaDataKind.DATA ? RotationOutcome.ROTATED : RotationOutcome.MISSING;
		});
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeLogsWarnWhenABlobIsStillPlaintext() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenReturn(RotationOutcome.NOT_ENCRYPTED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		ResultContext context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
		assertEquals(false, context.getProcessed());
	}

	@Test
	void aFailingThumbnailDoesNotStopTheOtherBlobsBeingRotatedButIsReported() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.rotateKek(any(FileMetaData.class))).thenAnswer(invocation -> {
			FileMetaData metadata = invocation.getArgument(0);
			if (metadata.getKind() == FileMetaDataKind.THUMBNAIL_SMALL) {
				throw new IOException("boom");
			}
			return RotationOutcome.ROTATED;
		});
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL, ThumbnailType.LARGE);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		RotateKekBatchImpl batch = new RotateKekBatchImpl(accountRepository, documentRepository, fileDataStore);

		assertThrows(BatchBusinessException.class,
				() -> batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0));

		// DATA, SMALL (throws) and LARGE were all attempted.
		verify(fileDataStore, org.mockito.Mockito.times(3)).rotateKek(any(FileMetaData.class));
	}
}
