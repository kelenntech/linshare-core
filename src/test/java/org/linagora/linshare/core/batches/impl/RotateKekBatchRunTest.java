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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.FileSystemJcloudFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.KekRotator;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;
import org.linagora.linshare.core.runner.impl.BatchRunnerImpl;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobFormat;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.KeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.RotatableKeyEncryptionService;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

/**
 * Runs {@link RotateKekBatchImpl} through the real {@link BatchRunnerImpl}
 * over real encrypted blobs on disk, with one bad blob between two good
 * ones. The runner stops the whole run on any exception that escapes
 * execute() other than a BatchBusinessException/BusinessException, so a
 * problem specific to one blob must come back as an outcome instead:
 * otherwise every document after it is never rotated, on every run.
 */
class RotateKekBatchRunTest {

	private static final EncryptionParameters PARAMS = new EncryptionParameters(64 * 1024,
			EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY, EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);

	private final DocumentRepository documentRepository = mock(DocumentRepository.class);

	private final KeyEncryptionService previousKey = new LocalKeyEncryptionService(randomKey(), "kek-2025");

	private final KeyEncryptionService currentKey = new LocalKeyEncryptionService(randomKey(), "kek-2026");

	private static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private static byte[] randomBytes(int length, int seed) {
		byte[] bytes = new byte[length];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	private FileDataStore newRawStore(Path directory) {
		return new FileSystemJcloudFileDataStoreImpl(ImmutableSet.of(new SLF4JLoggingModule()), new Properties(),
				"test-bucket", directory.toString());
	}

	/** Built the way EncryptedFileDataStoreFactory builds it once previous-key-id is set. */
	private EncryptedFileDataStoreImpl rotatingStore(FileDataStore rawStore) {
		return new EncryptedFileDataStoreImpl(rawStore,
				new RotatableKeyEncryptionService("kek-2026", currentKey, "kek-2025", previousKey), PARAMS, true,
				true, true, "kek-2026", new KekRotator(rawStore, "kek-2025", previousKey, currentKey));
	}

	private FileMetaData storeUnder(FileDataStore rawStore, KeyEncryptionService key, String uuid, byte[] plaintext)
			throws IOException {
		EncryptedFileDataStoreImpl store = new EncryptedFileDataStoreImpl(rawStore, key, PARAMS, true, true, true,
				null, null);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "f.bin");
		metadata.setUuid(uuid);
		return store.add(ByteSource.wrap(plaintext), metadata);
	}

	private FileMetaData storeRaw(FileDataStore rawStore, String uuid, byte[] bytes) throws IOException {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) bytes.length, "f.bin");
		metadata.setUuid(uuid);
		return rawStore.add(ByteSource.wrap(bytes), metadata);
	}

	private void registerDocuments(FileMetaData... blobs) {
		String[] uuids = new String[blobs.length];
		for (int i = 0; i < blobs.length; i++) {
			Document document = new Document();
			document.setUuid(blobs[i].getUuid());
			document.setBucketUuid(blobs[i].getBucketUuid());
			document.setHasThumbnail(false);
			document.setSize(blobs[i].getSize());
			when(documentRepository.findByUuid(blobs[i].getUuid())).thenReturn(document);
			uuids[i] = blobs[i].getUuid();
		}
		when(documentRepository.findAllIdentifiers()).thenReturn(List.of(uuids));
	}

	@SuppressWarnings("unchecked")
	private boolean runRotation(FileDataStore fileDataStore) {
		RotateKekBatchImpl batch = new RotateKekBatchImpl(mock(AccountRepository.class), documentRepository,
				fileDataStore);
		return new BatchRunnerImpl().execute(batch, new BatchRunContext());
	}

	private byte[] rawBytes(FileDataStore rawStore, FileMetaData metadata) throws IOException {
		try (InputStream in = rawStore.get(metadata).openStream()) {
			return ByteStreams.toByteArray(in);
		}
	}

	private String keyIdOf(FileDataStore rawStore, FileMetaData metadata) throws IOException {
		try (InputStream in = rawStore.get(metadata).openStream()) {
			return EncryptedBlobFormat.readHeader(in).getKeyId();
		}
	}

	private void assertRotatedAndReadable(FileDataStore rawStore, EncryptedFileDataStoreImpl store,
			FileMetaData metadata, byte[] plaintext) throws IOException {
		assertEquals("kek-2026", keyIdOf(rawStore, metadata), metadata.getUuid() + " was not rotated");
		try (InputStream in = store.get(metadata).openStream()) {
			assertArrayEquals(plaintext, ByteStreams.toByteArray(in));
		}
	}

	@Test
	void aBlobUnderAnUnknownKeyIdDoesNotStopTheRun(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawStore(tempDir);
		byte[] first = randomBytes(1000, 1);
		byte[] third = randomBytes(1000, 3);
		FileMetaData doc1 = storeUnder(rawStore, previousKey, "doc-1", first);
		// From two rotations ago: neither the previous nor the current key.
		FileMetaData doc2 = storeUnder(rawStore, new LocalKeyEncryptionService(randomKey(), "kek-2024"), "doc-2",
				randomBytes(1000, 2));
		FileMetaData doc3 = storeUnder(rawStore, previousKey, "doc-3", third);
		byte[] doc2Before = rawBytes(rawStore, doc2);
		registerDocuments(doc1, doc2, doc3);
		EncryptedFileDataStoreImpl store = rotatingStore(rawStore);

		assertTrue(runRotation(store), "the run must not be stopped by one blob");

		assertRotatedAndReadable(rawStore, store, doc1, first);
		assertRotatedAndReadable(rawStore, store, doc3, third);
		assertArrayEquals(doc2Before, rawBytes(rawStore, doc2), "the unknown-key blob must be left untouched");
	}

	@Test
	void aBlobWithACorruptedHeaderDoesNotStopTheRun(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawStore(tempDir);
		byte[] first = randomBytes(1000, 1);
		byte[] third = randomBytes(1000, 3);
		FileMetaData doc1 = storeUnder(rawStore, previousKey, "doc-1", first);
		// A valid LSE1 magic followed by a truncated header.
		byte[] magic = EncryptedBlobHeader.magic();
		byte[] truncated = Arrays.copyOf(magic, magic.length + 3);
		FileMetaData doc2 = storeRaw(rawStore, "doc-2", truncated);
		FileMetaData doc3 = storeUnder(rawStore, previousKey, "doc-3", third);
		registerDocuments(doc1, doc2, doc3);
		EncryptedFileDataStoreImpl store = rotatingStore(rawStore);

		assertTrue(runRotation(store), "the run must not be stopped by one blob");

		assertRotatedAndReadable(rawStore, store, doc1, first);
		assertRotatedAndReadable(rawStore, store, doc3, third);
		assertArrayEquals(truncated, rawBytes(rawStore, doc2), "the damaged blob must be left untouched");
	}

	@Test
	void aBlobWithANegativeWrappedKeyLengthDoesNotStopTheRun(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawStore(tempDir);
		byte[] first = randomBytes(1000, 1);
		byte[] third = randomBytes(1000, 3);
		FileMetaData doc1 = storeUnder(rawStore, previousKey, "doc-1", first);
		// A valid blob with 4 corrupted bytes: its wrappedKeyLength made negative.
		byte[] corrupted = rawBytes(rawStore, storeUnder(rawStore, previousKey, "doc-2-source", randomBytes(1000, 2)));
		java.nio.ByteBuffer.wrap(corrupted).putInt(EncryptedBlobHeader.FIXED_HEADER_LENGTH - 8, -1);
		FileMetaData doc2 = storeRaw(rawStore, "doc-2", corrupted);
		FileMetaData doc3 = storeUnder(rawStore, previousKey, "doc-3", third);
		registerDocuments(doc1, doc2, doc3);
		EncryptedFileDataStoreImpl store = rotatingStore(rawStore);

		assertTrue(runRotation(store), "the run must not be stopped by one blob");

		assertRotatedAndReadable(rawStore, store, doc1, first);
		assertRotatedAndReadable(rawStore, store, doc3, third);
		assertArrayEquals(corrupted, rawBytes(rawStore, doc2), "the damaged blob must be left untouched");
	}
}
