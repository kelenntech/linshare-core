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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Stream;

import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.KeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.inject.Module;

/**
 * Exercises {@link EncryptedFileDataStoreImpl} against a real jclouds
 * filesystem provider on real disk — the ARCH.md 33 / CLAUDE.md 33 first
 * milestone: upload, confirm the persistent bytes are not plaintext, full
 * download matches the original, and an existing legacy plaintext blob
 * remains readable.
 */
class EncryptedFileSystemJcloudIntegrationTest {

	private static final Iterable<Module> MODULES = ImmutableSet.of(new SLF4JLoggingModule());

	private FileDataStore newRawFilesystemStore(Path baseDirectory) {
		return new FileSystemJcloudFileDataStoreImpl(MODULES, new Properties(), "test-bucket",
				baseDirectory.toString());
	}

	private EncryptedFileDataStoreImpl newEncryptedStore(FileDataStore delegate, boolean allowLegacyRead) {
		KeyEncryptionService keyEncryptionService = new LocalKeyEncryptionService(randomMasterKey(), "test-kek");
		EncryptionParameters params = new EncryptionParameters(64 * 1024, EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY,
				EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);
		return new EncryptedFileDataStoreImpl(delegate, keyEncryptionService, params, true, true, allowLegacyRead,
				null, null);
	}

	private byte[] randomMasterKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	@Test
	void uploadPersistsCiphertextAndDownloadMatchesOriginalSha256(@TempDir Path tempDir) throws Exception {
		byte[] plaintext = randomBytes(64 * 1024 * 3 + 777); // several chunks, non-aligned tail
		FileDataStore rawStore = newRawFilesystemStore(tempDir);
		EncryptedFileDataStoreImpl store = newEncryptedStore(rawStore, true);

		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "large-file.bin");
		FileMetaData stored = store.add(ByteSource.wrap(plaintext), metadata);

		assertEquals(plaintext.length, stored.getSize());
		assertSame(metadata, stored);

		Path persistedFile = findPersistedFile(tempDir, stored.getUuid());
		byte[] persisted = Files.readAllBytes(persistedFile);
		assertFalse(containsSubsequence(persisted, plaintext), "persisted bytes must not contain the plaintext");
		byte[] magicPrefix = Arrays.copyOf(persisted, EncryptedBlobHeader.magic().length);
		assertArrayEquals(EncryptedBlobHeader.magic(), magicPrefix);

		byte[] downloaded;
		try (InputStream in = store.get(stored).openStream()) {
			downloaded = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(sha256(plaintext), sha256(downloaded));

		store.remove(stored);
		assertFalse(store.exists(stored));
	}

	@Test
	void legacyPlaintextBlobWrittenDirectlyToDiskRemainsReadable(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawFilesystemStore(tempDir);
		EncryptedFileDataStoreImpl store = newEncryptedStore(rawStore, true);

		byte[] legacyPlaintext = "legacy unencrypted content, predates this fork".getBytes(StandardCharsets.UTF_8);
		FileMetaData legacyMetadata = new FileMetaData(FileMetaDataKind.DATA, "text/plain",
				(long) legacyPlaintext.length, "legacy.txt");
		// Written through the RAW (unwrapped) store, exactly as an install
		// upgraded from before this fork existed would already have on disk.
		rawStore.add(ByteSource.wrap(legacyPlaintext), legacyMetadata);

		byte[] readBack;
		try (InputStream in = store.get(legacyMetadata).openStream()) {
			readBack = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(legacyPlaintext, readBack);
	}

	@Test
	void migrationOfLegacyBlobOnRealFilesystemUsesAtomicRename(@TempDir Path tempDir) throws Exception {
		FileSystemJcloudFileDataStoreImpl rawStore = (FileSystemJcloudFileDataStoreImpl) newRawFilesystemStore(
				tempDir);
		byte[] legacyPlaintext = randomBytes(64 * 1024 * 2 + 123);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) legacyPlaintext.length, "legacy-large.bin");
		FileMetaData stored = rawStore.add(ByteSource.wrap(legacyPlaintext), metadata);

		KeyEncryptionService keyEncryptionService = new LocalKeyEncryptionService(randomMasterKey(), "test-kek");
		EncryptionParameters params = new EncryptionParameters(64 * 1024, EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY,
				EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);
		EncryptedBlobMigrator migrator = new EncryptedBlobMigrator(rawStore, keyEncryptionService, params);

		MigrationOutcome outcome = migrator.migrate(stored, sha256Hex(legacyPlaintext));
		assertEquals(MigrationOutcome.MIGRATED, outcome);

		// No leftover temp file anywhere under the store's directory.
		try (Stream<Path> paths = Files.walk(tempDir)) {
			assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".migrating")),
					"no temp migration artifact should remain after a successful commit");
		}

		Path persistedFile = findPersistedFile(tempDir, stored.getUuid());
		byte[] persisted = Files.readAllBytes(persistedFile);
		assertArrayEquals(EncryptedBlobHeader.magic(), Arrays.copyOf(persisted, 4));

		EncryptedFileDataStoreImpl decoratedStore = new EncryptedFileDataStoreImpl(rawStore, keyEncryptionService,
				params, true, true, true, null, null);
		byte[] decrypted;
		try (InputStream in = decoratedStore.get(stored).openStream()) {
			decrypted = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(legacyPlaintext, decrypted);

		// Idempotent: migrating again is a clean, harmless no-op.
		assertEquals(MigrationOutcome.ALREADY_ENCRYPTED, migrator.migrate(stored, sha256Hex(legacyPlaintext)));
	}

	@Test
	void kekRotationOnRealFilesystemUsesAtomicRenameAndLeavesChunksByteIdentical(@TempDir Path tempDir)
			throws Exception {
		FileSystemJcloudFileDataStoreImpl rawStore = (FileSystemJcloudFileDataStoreImpl) newRawFilesystemStore(
				tempDir);
		KeyEncryptionService oldKeyService = new LocalKeyEncryptionService(randomMasterKey(), "old-kek");
		EncryptionParameters params = new EncryptionParameters(64 * 1024, EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY,
				EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);
		byte[] plaintext = randomBytes(64 * 1024 * 2 + 321);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "rotate-me.bin");
		EncryptedFileDataStoreImpl encryptedStore = new EncryptedFileDataStoreImpl(rawStore, oldKeyService, params,
				true, true, true, null, null);
		FileMetaData stored = encryptedStore.add(ByteSource.wrap(plaintext), metadata);

		Path persistedFile = findPersistedFile(tempDir, stored.getUuid());
		byte[] beforeRotation = Files.readAllBytes(persistedFile);
		long headerLength = org.linagora.linshare.storage.encryption.format.ChunkLayout
				.of(org.linagora.linshare.storage.encryption.format.EncryptedBlobFormat
						.readHeader(new java.io.ByteArrayInputStream(beforeRotation)))
				.headerTotalLength();

		KeyEncryptionService newKeyService = new LocalKeyEncryptionService(randomMasterKey(), "new-kek");
		KekRotator rotator = new KekRotator(rawStore, oldKeyService, newKeyService);

		RotationOutcome outcome = rotator.rotate(stored, "new-kek");
		assertEquals(RotationOutcome.ROTATED, outcome);

		try (Stream<Path> paths = Files.walk(tempDir)) {
			assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".rewrapping")),
					"no temp rotation artifact should remain after a successful commit");
		}

		byte[] afterRotation = Files.readAllBytes(persistedFile);
		byte[] chunksBefore = Arrays.copyOfRange(beforeRotation, (int) headerLength, beforeRotation.length);
		byte[] chunksAfter = Arrays.copyOfRange(afterRotation, (int) headerLength, afterRotation.length);
		assertArrayEquals(chunksBefore, chunksAfter, "chunk ciphertext must be untouched by rotation");

		EncryptedFileDataStoreImpl rotatedStore = new EncryptedFileDataStoreImpl(rawStore, newKeyService, params,
				true, true, true, null, null);
		byte[] decrypted;
		try (InputStream in = rotatedStore.get(stored).openStream()) {
			decrypted = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(plaintext, decrypted);

		assertEquals(RotationOutcome.ALREADY_ROTATED, rotator.rotate(stored, "new-kek"));
	}

	@Test
	void rotateKekThroughEncryptedFileDataStoreImplKeepsNotYetRotatedBlobsReadable(@TempDir Path tempDir)
			throws Exception {
		FileSystemJcloudFileDataStoreImpl rawStore = (FileSystemJcloudFileDataStoreImpl) newRawFilesystemStore(
				tempDir);
		KeyEncryptionService oldKeyService = new LocalKeyEncryptionService(randomMasterKey(), "old-kek");
		KeyEncryptionService newKeyService = new LocalKeyEncryptionService(randomMasterKey(), "new-kek");
		EncryptionParameters params = new EncryptionParameters(64 * 1024, EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY,
				EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);

		// Mirrors what EncryptedFileDataStoreFactory builds once a previous
		// key is configured: reads span both keys, writes always target the
		// new one, and the store owns a KekRotator built from the same pair.
		KeyEncryptionService rotatableKeyService = new org.linagora.linshare.storage.encryption.key.RotatableKeyEncryptionService(
				"new-kek", newKeyService, "old-kek", oldKeyService);
		KekRotator rotator = new KekRotator(rawStore, oldKeyService, newKeyService);
		EncryptedFileDataStoreImpl store = new EncryptedFileDataStoreImpl(rawStore, rotatableKeyService, params, true,
				true, true, "new-kek", rotator);

		byte[] plaintextA = randomBytes(1000);
		FileMetaData notYetRotated = store.add(ByteSource.wrap(plaintextA),
				new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream", (long) plaintextA.length,
						"not-yet-rotated.bin"));

		// A blob written before rotation is encrypted under old-kek (the
		// store's own writes always target new-kek once it holds the
		// composite key service pointed at the new key for wrap, but this
		// simulates a blob that already existed under the previous key,
		// analogous to migrationOfLegacyBlobOnRealFilesystemUsesAtomicRename
		// above): write it directly under the raw store with the old key.
		EncryptedFileDataStoreImpl oldOnlyStore = new EncryptedFileDataStoreImpl(rawStore, oldKeyService, params,
				true, true, true, "old-kek", null);
		byte[] plaintextB = randomBytes(500);
		FileMetaData legacyKeyed = oldOnlyStore.add(ByteSource.wrap(plaintextB), new FileMetaData(
				FileMetaDataKind.DATA, "application/octet-stream", (long) plaintextB.length, "old-keyed.bin"));

		// Before rotation runs, the composite-backed store can still read a
		// blob encrypted under the old key.
		byte[] readBeforeRotation;
		try (InputStream in = store.get(legacyKeyed).openStream()) {
			readBeforeRotation = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(plaintextB, readBeforeRotation);

		assertTrue(store.isRotationConfigured());
		RotationOutcome outcome = store.rotateKek(legacyKeyed);
		assertEquals(RotationOutcome.ROTATED, outcome);

		byte[] readAfterRotation;
		try (InputStream in = store.get(legacyKeyed).openStream()) {
			readAfterRotation = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(plaintextB, readAfterRotation);

		// The freshly-written blob (already under new-kek) is untouched.
		byte[] readNotYetRotated;
		try (InputStream in = store.get(notYetRotated).openStream()) {
			readNotYetRotated = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(plaintextA, readNotYetRotated);
	}

	private static String sha256Hex(byte[] data) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	@Test
	void rawFilesystemStoreHonorsPhysicalByteRanges(@TempDir Path tempDir) throws Exception {
		// Validates the jclouds GetOptions.range() plumbing itself
		// (AbstractJcloudFileDataStoreImpl.getRange), independent of encryption.
		FileDataStore rawStore = newRawFilesystemStore(tempDir);
		byte[] content = randomBytes(1000);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) content.length, "raw.bin");
		FileMetaData stored = rawStore.add(ByteSource.wrap(content), metadata);

		byte[] slice;
		try (InputStream in = rawStore.getRange(stored, 100, 50).openStream()) {
			slice = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(Arrays.copyOfRange(content, 100, 150), slice);
	}

	@Test
	void encryptedRangeReadOnRealFilesystemCrossesChunkBoundary(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawFilesystemStore(tempDir);
		EncryptedFileDataStoreImpl store = newEncryptedStore(rawStore, true);

		byte[] plaintext = randomBytes(64 * 1024 * 3 + 777);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "large-file.bin");
		FileMetaData stored = store.add(ByteSource.wrap(plaintext), metadata);

		long offset = 64 * 1024 - 5; // 5 bytes before the first chunk boundary
		long length = 20; // crosses into the second chunk

		byte[] rangeResult;
		try (InputStream in = store.getRange(stored, offset, length).openStream()) {
			rangeResult = ByteStreams.toByteArray(in);
		}
		assertArrayEquals(Arrays.copyOfRange(plaintext, (int) offset, (int) (offset + length)), rangeResult);
	}

	private static Path findPersistedFile(Path tempDir, String uuid) throws IOException {
		try (Stream<Path> paths = Files.walk(tempDir)) {
			return paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(uuid)).findFirst()
					.orElseThrow(() -> new AssertionError("persisted blob file not found for uuid " + uuid));
		}
	}

	private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static byte[] sha256(byte[] data) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random(21).nextBytes(bytes);
		return bytes;
	}
}
