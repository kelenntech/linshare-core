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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.linagora.linshare.core.batches.utils.BatchConsole;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.MigrationOutcome;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.constants.ThumbnailType;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.entities.Thumbnail;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.exception.BatchBusinessException;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;

/**
 * Focused on the WARN-level logging fix: MISSING/VERIFICATION_FAILED must
 * be visible at default log verbosity, unlike the everyday ALREADY_ENCRYPTED
 * no-op, since execute() never throws for a bad outcome so notifyError()
 * can never fire for these (see MigrateLegacyDocumentsBatchImpl#logOutcome).
 */
class MigrateLegacyDocumentsBatchImplTest {

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

	private MigrateLegacyDocumentsBatchImpl newBatch(EncryptedFileDataStoreImpl fileDataStore) {
		return new MigrateLegacyDocumentsBatchImpl(accountRepository, documentRepository, fileDataStore);
	}

	@Test
	void executeMarksProcessedWhenOutcomeIsMigrated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenReturn(MigrationOutcome.MIGRATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		var context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeSkipsDocumentsWithoutABucketUuid() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		Document document = new Document();
		document.setUuid("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		var context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(false, context.getProcessed());
	}

	@Test
	void executeWrapsIOExceptionAsBatchBusinessException() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenThrow(new IOException("boom"));
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		assertThrows(BatchBusinessException.class,
				() -> batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0));
	}

	@Test
	void executeLogsWarnWhenBlobIsMissing() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenReturn(MigrationOutcome.MISSING);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void executeLogsWarnWhenVerificationFails() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any()))
				.thenReturn(MigrationOutcome.VERIFICATION_FAILED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void executeDoesNotLogWarnWhenAlreadyEncrypted() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any()))
				.thenReturn(MigrationOutcome.ALREADY_ENCRYPTED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console, never()).logWarn(any(BatchRunContext.class), anyLong(), anyLong(), any(), any());
	}

	@Test
	void executeDoesNotLogWarnWhenMigrated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenReturn(MigrationOutcome.MIGRATED);
		Document document = documentWithBucket("doc-1");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console, never()).logWarn(any(BatchRunContext.class), anyLong(), anyLong(), any(), any());
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
	void executeMigratesTheDocumentsThumbnailsWithoutAKnownSha256() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenReturn(MigrationOutcome.MIGRATED);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class))).thenReturn(MigrationOutcome.MIGRATED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL, ThumbnailType.MEDIUM,
				ThumbnailType.LARGE);
		document.setThmbUuid("doc-1-legacy-thmb");
		document.setSha256sum("abc123");
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		var context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		// The DATA blob keeps its integrity check against the stored SHA-256.
		ArgumentCaptor<FileMetaData> data = ArgumentCaptor.forClass(FileMetaData.class);
		verify(fileDataStore).migrateLegacyBlob(data.capture(), eq("abc123"));
		assertEquals(FileMetaDataKind.DATA, data.getValue().getKind());
		assertEquals("doc-1", data.getValue().getUuid());
		// Thumbnails have none recorded, so they go through the size/hash-less variant.
		ArgumentCaptor<FileMetaData> thumbnails = ArgumentCaptor.forClass(FileMetaData.class);
		verify(fileDataStore, org.mockito.Mockito.times(4)).migrateLegacyBlob(thumbnails.capture());
		Map<FileMetaDataKind, String> uuidByKind = new EnumMap<>(FileMetaDataKind.class);
		for (FileMetaData metadata : thumbnails.getAllValues()) {
			uuidByKind.put(metadata.getKind(), metadata.getUuid());
			assertEquals("bucket-1", metadata.getBucketUuid());
		}
		assertEquals("doc-1-thmb-small", uuidByKind.get(FileMetaDataKind.THUMBNAIL_SMALL));
		assertEquals("doc-1-thmb-medium", uuidByKind.get(FileMetaDataKind.THUMBNAIL_MEDIUM));
		assertEquals("doc-1-thmb-large", uuidByKind.get(FileMetaDataKind.THUMBNAIL_LARGE));
		assertEquals("doc-1-legacy-thmb", uuidByKind.get(FileMetaDataKind.THUMBNAIL));
		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeToleratesAThumbnailTypeTheDocumentDoesNotHave() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any()))
				.thenReturn(MigrationOutcome.ALREADY_ENCRYPTED);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class)))
				.thenReturn(MigrationOutcome.ALREADY_ENCRYPTED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		var context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(fileDataStore, org.mockito.Mockito.times(1)).migrateLegacyBlob(any(FileMetaData.class));
		assertEquals(false, context.getProcessed());
	}

	@Test
	void executeIsProcessedWhenOnlyAThumbnailNeededMigrating() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any()))
				.thenReturn(MigrationOutcome.ALREADY_ENCRYPTED);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class))).thenReturn(MigrationOutcome.MIGRATED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		var context = batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		assertEquals(true, context.getProcessed());
	}

	@Test
	void executeLogsWarnWhenAThumbnailFailsVerification() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any()))
				.thenReturn(MigrationOutcome.ALREADY_ENCRYPTED);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class)))
				.thenReturn(MigrationOutcome.VERIFICATION_FAILED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);
		BatchConsole console = mock(BatchConsole.class);
		batch.setConsole(console);

		batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0);

		verify(console).logWarn(any(BatchRunContext.class), eq(1L), eq(0L), any(), eq("doc-1"));
	}

	@Test
	void aFailingThumbnailDoesNotStopTheOtherBlobsBeingMigratedButIsReported() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenReturn(MigrationOutcome.MIGRATED);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class))).thenAnswer(invocation -> {
			FileMetaData metadata = invocation.getArgument(0);
			if (metadata.getKind() == FileMetaDataKind.THUMBNAIL_SMALL) {
				throw new IOException("boom");
			}
			return MigrationOutcome.MIGRATED;
		});
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL, ThumbnailType.LARGE);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		assertThrows(BatchBusinessException.class,
				() -> batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0));

		verify(fileDataStore, org.mockito.Mockito.times(2)).migrateLegacyBlob(any(FileMetaData.class));
	}

	@Test
	void aFailingMainBlobDoesNotStopTheThumbnailsBeingMigrated() throws Exception {
		EncryptedFileDataStoreImpl fileDataStore = mock(EncryptedFileDataStoreImpl.class);
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class), any())).thenThrow(new IOException("boom"));
		when(fileDataStore.migrateLegacyBlob(any(FileMetaData.class))).thenReturn(MigrationOutcome.MIGRATED);
		Document document = documentWithThumbnails("doc-1", ThumbnailType.SMALL);
		when(documentRepository.findByUuid("doc-1")).thenReturn(document);
		MigrateLegacyDocumentsBatchImpl batch = newBatch(fileDataStore);

		assertThrows(BatchBusinessException.class,
				() -> batch.execute(mock(BatchRunContext.class), "doc-1", 1, 0));

		verify(fileDataStore).migrateLegacyBlob(any(FileMetaData.class));
	}
}
