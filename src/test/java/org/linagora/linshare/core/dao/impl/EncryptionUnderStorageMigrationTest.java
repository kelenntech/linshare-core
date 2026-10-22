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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobFormat;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.KeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

/**
 * Encryption migration and KEK rotation over a real
 * {@link MigrationFileDataStoreImpl} (the migration-gridfs-to-jcloud
 * profile's raw store), with two real filesystem stores standing in for
 * jcloud (new) and GridFS (old). add() always writes to the new store, so a
 * temp blob for a document still in the old store lands on a different
 * backend than its target and can never be atomically committed: those
 * documents must be skipped up front, not leave an orphan temp behind and
 * fail the same way on every run.
 */
class EncryptionUnderStorageMigrationTest {

	private static final EncryptionParameters PARAMS = new EncryptionParameters(64 * 1024,
			EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY, EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);

	private static FileDataStore filesystemStore(Path directory) {
		return new FileSystemJcloudFileDataStoreImpl(ImmutableSet.of(new SLF4JLoggingModule()), new Properties(),
				"test-bucket", directory.toString());
	}

	private static KeyEncryptionService key(String keyId) {
		byte[] master = new byte[32];
		new SecureRandom().nextBytes(master);
		return new LocalKeyEncryptionService(master, keyId);
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random(41).nextBytes(bytes);
		return bytes;
	}

	private static String sha256Hex(byte[] data) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
			sb.append(String.format(Locale.ROOT, "%02x", b));
		}
		return sb.toString();
	}

	private static FileMetaData storePlaintext(FileDataStore store, String uuid, byte[] plaintext)
			throws IOException {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "f.bin");
		metadata.setUuid(uuid);
		return store.add(ByteSource.wrap(plaintext), metadata);
	}

	private static FileMetaData withSuffix(FileMetaData metadata, String suffix) {
		FileMetaData temp = new FileMetaData(metadata.getKind(), metadata.getMimeType(), metadata.getSize(),
				metadata.getFileName());
		temp.setUuid(metadata.getUuid() + suffix);
		temp.setBucketUuid(metadata.getBucketUuid());
		return temp;
	}

	private static byte[] bytes(FileDataStore store, FileMetaData metadata) throws IOException {
		try (InputStream in = store.get(metadata).openStream()) {
			return ByteStreams.toByteArray(in);
		}
	}

	@Test
	void migrationSkipsADocumentStillInTheOldStoreWithoutLeavingATemp(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		MigrationFileDataStoreImpl store = new MigrationFileDataStoreImpl(newStore, oldStore);
		byte[] plaintext = randomBytes(1000);
		FileMetaData metadata = storePlaintext(oldStore, "doc-1", plaintext);
		EncryptedBlobMigrator migrator = new EncryptedBlobMigrator(store, key("kek"), PARAMS);

		assertEquals(MigrationOutcome.STORAGE_MIGRATION_PENDING, migrator.migrate(metadata, sha256Hex(plaintext)));

		assertFalse(store.exists(withSuffix(metadata, ".migrating")), "no orphan temp may be left behind");
		assertArrayEquals(plaintext, bytes(oldStore, metadata), "the old-store blob must be left untouched");
		assertFalse(newStore.exists(metadata));
	}

	@Test
	void migrationCleansUpAnOrphanTempLeftByEarlierRuns(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		MigrationFileDataStoreImpl store = new MigrationFileDataStoreImpl(newStore, oldStore);
		byte[] plaintext = randomBytes(1000);
		FileMetaData metadata = storePlaintext(oldStore, "doc-1", plaintext);
		// What earlier runs left in the new store before this fix.
		storePlaintext(newStore, "doc-1.migrating", randomBytes(10));
		EncryptedBlobMigrator migrator = new EncryptedBlobMigrator(store, key("kek"), PARAMS);

		assertEquals(MigrationOutcome.STORAGE_MIGRATION_PENDING, migrator.migrate(metadata, sha256Hex(plaintext)));

		assertFalse(store.exists(withSuffix(metadata, ".migrating")));
	}

	@Test
	void migrationStillMigratesADocumentAlreadyInTheNewStore(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		MigrationFileDataStoreImpl store = new MigrationFileDataStoreImpl(newStore, filesystemStore(oldDir));
		byte[] plaintext = randomBytes(1000);
		FileMetaData metadata = storePlaintext(newStore, "doc-1", plaintext);
		KeyEncryptionService key = key("kek");

		assertEquals(MigrationOutcome.MIGRATED,
				new EncryptedBlobMigrator(store, key, PARAMS).migrate(metadata, sha256Hex(plaintext)));

		try (InputStream in = new EncryptedFileDataStoreImpl(store, key, PARAMS, true, true, true, null, null)
				.get(metadata).openStream()) {
			assertArrayEquals(plaintext, ByteStreams.toByteArray(in));
		}
		assertFalse(store.exists(withSuffix(metadata, ".migrating")));
	}

	@Test
	void rotationSkipsADocumentStillInTheOldStoreWithoutLeavingATemp(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		FileDataStore oldStore = filesystemStore(oldDir);
		MigrationFileDataStoreImpl store = new MigrationFileDataStoreImpl(newStore, oldStore);
		KeyEncryptionService oldKey = key("old-kek");
		FileMetaData metadata = new EncryptedFileDataStoreImpl(oldStore, oldKey, PARAMS, true, true, true, null,
				null).add(ByteSource.wrap(randomBytes(1000)), new FileMetaData(FileMetaDataKind.DATA,
						"application/octet-stream", 1000L, "f.bin"));
		byte[] before = bytes(oldStore, metadata);
		KekRotator rotator = new KekRotator(store, "old-kek", oldKey, key("new-kek"));

		assertEquals(RotationOutcome.STORAGE_MIGRATION_PENDING, rotator.rotate(metadata, "new-kek"));

		assertFalse(store.exists(withSuffix(metadata, ".rewrapping")), "no orphan temp may be left behind");
		assertArrayEquals(before, bytes(oldStore, metadata), "the old-store blob must be left untouched");
	}

	@Test
	void rotationStillRotatesADocumentAlreadyInTheNewStore(@TempDir Path newDir, @TempDir Path oldDir)
			throws Exception {
		FileDataStore newStore = filesystemStore(newDir);
		MigrationFileDataStoreImpl store = new MigrationFileDataStoreImpl(newStore, filesystemStore(oldDir));
		KeyEncryptionService oldKey = key("old-kek");
		FileMetaData metadata = new EncryptedFileDataStoreImpl(newStore, oldKey, PARAMS, true, true, true, null,
				null).add(ByteSource.wrap(randomBytes(1000)), new FileMetaData(FileMetaDataKind.DATA,
						"application/octet-stream", 1000L, "f.bin"));

		assertEquals(RotationOutcome.ROTATED,
				new KekRotator(store, "old-kek", oldKey, key("new-kek")).rotate(metadata, "new-kek"));

		try (InputStream in = store.get(metadata).openStream()) {
			assertEquals("new-kek", EncryptedBlobFormat.readHeader(in).getKeyId());
		}
		assertFalse(store.exists(withSuffix(metadata, ".rewrapping")));
	}
}
