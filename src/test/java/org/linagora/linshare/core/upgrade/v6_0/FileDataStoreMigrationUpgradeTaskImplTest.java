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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linagora.linshare.core.batches.utils.BatchConsole;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.FileSystemJcloudFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.MigrationFileDataStoreImpl;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.job.quartz.ResultContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;
import org.linagora.linshare.mongo.repository.UpgradeTaskLogMongoRepository;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.KeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

/**
 * The gridfs-to-jcloud storage migration over a real
 * {@link MigrationFileDataStoreImpl} (two filesystem stores standing in for
 * jcloud and GridFS). It is given the raw store, so blobs are copied exactly
 * as stored: an encrypted one moves as ciphertext, which is larger than the
 * Document's (plaintext) size that jcloud would otherwise send as the
 * Content-Length.
 */
class FileDataStoreMigrationUpgradeTaskImplTest {

	private static final EncryptionParameters PARAMS = new EncryptionParameters(64 * 1024,
			EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY, EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);

	private final DocumentRepository documentRepository = mock(DocumentRepository.class);

	private static FileDataStore filesystemStore(Path directory) {
		return new FileSystemJcloudFileDataStoreImpl(ImmutableSet.of(new SLF4JLoggingModule()), new Properties(),
				"test-bucket", directory.toString());
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random(43).nextBytes(bytes);
		return bytes;
	}

	private static KeyEncryptionService key() {
		byte[] master = new byte[32];
		new SecureRandom().nextBytes(master);
		return new LocalKeyEncryptionService(master, "kek");
	}

	private static byte[] bytes(FileDataStore store, FileMetaData metadata) throws IOException {
		try (InputStream in = store.get(metadata).openStream()) {
			return ByteStreams.toByteArray(in);
		}
	}

	private Document document(FileMetaData stored, long plaintextSize) {
		Document document = new Document();
		document.setUuid(stored.getUuid());
		document.setBucketUuid(stored.getBucketUuid());
		document.setType("application/octet-stream");
		document.setSize(plaintextSize);
		document.setHasThumbnail(false);
		document.setToUpgrade(true);
		document.setCreationDate(java.util.Calendar.getInstance()); // read by Document.toString()
		when(documentRepository.findByUuid(stored.getUuid())).thenReturn(document);
		return document;
	}

	@SuppressWarnings("unchecked")
	private ResultContext runOn(FileDataStore rawFileDataStore, String uuid) {
		FileDataStoreMigrationUpgradeTaskImpl task = new FileDataStoreMigrationUpgradeTaskImpl(
				mock(AccountRepository.class), mock(UpgradeTaskLogMongoRepository.class), documentRepository,
				rawFileDataStore);
		task.setConsole(mock(BatchConsole.class));
		return task.execute(new BatchRunContext(), uuid, 1, 1);
	}

	@Test
	void copiesAnEncryptedBlobVerbatimWithItsStoredSize(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		byte[] plaintext = randomBytes(1000);
		KeyEncryptionService key = key();
		FileMetaData stored = new EncryptedFileDataStoreImpl(oldStore, key, PARAMS, true, true, true, null, null)
				.add(ByteSource.wrap(plaintext), new FileMetaData(FileMetaDataKind.DATA,
						"application/octet-stream", (long) plaintext.length, "f.bin"));
		byte[] ciphertext = bytes(oldStore, stored);
		Document document = document(stored, plaintext.length);
		MigrationFileDataStoreImpl rawStore = new MigrationFileDataStoreImpl(newStore, oldStore);

		ResultContext result = runOn(rawStore, stored.getUuid());

		assertEquals(true, result.getProcessed());
		assertArrayEquals(ciphertext, bytes(newStore, stored), "the blob must be copied exactly as stored");
		assertFalse(document.getToUpgrade());
		verify(documentRepository).update(document);
		try (InputStream in = new EncryptedFileDataStoreImpl(rawStore, key, PARAMS, true, true, true, null, null)
				.get(stored).openStream()) {
			assertArrayEquals(plaintext, ByteStreams.toByteArray(in), "and must still decrypt from the new store");
		}
	}

	@Test
	void copiesALegacyPlaintextBlob(@TempDir Path newDir, @TempDir Path oldDir) throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		byte[] plaintext = randomBytes(1000);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "f.bin");
		metadata.setUuid("doc-1");
		FileMetaData stored = oldStore.add(ByteSource.wrap(plaintext), metadata);
		document(stored, plaintext.length);

		ResultContext result = runOn(new MigrationFileDataStoreImpl(newStore, oldStore), "doc-1");

		assertEquals(true, result.getProcessed());
		assertArrayEquals(plaintext, bytes(newStore, stored));
	}

	@Test
	void reportsACorruptEncryptionHeaderPerDocument(@TempDir Path newDir, @TempDir Path oldDir) throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		byte[] magic = EncryptedBlobHeader.magic();
		byte[] truncated = Arrays.copyOf(magic, magic.length + 3);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) truncated.length, "f.bin");
		metadata.setUuid("doc-1");
		FileMetaData stored = oldStore.add(ByteSource.wrap(truncated), metadata);
		Document document = document(stored, 1000);

		// Returned, not thrown: a runtime exception here would stop the whole upgrade task.
		ResultContext result = runOn(new MigrationFileDataStoreImpl(newStore, oldStore), "doc-1");

		assertEquals(false, result.getProcessed());
		assertFalse(newStore.exists(stored));
		verify(documentRepository, never()).update(document);
	}
}
