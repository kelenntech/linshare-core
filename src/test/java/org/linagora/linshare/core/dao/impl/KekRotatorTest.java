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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.dao.AtomicBlobReplace;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.storage.encryption.crypto.ChunkedDecryptor;
import org.linagora.linshare.storage.encryption.crypto.ChunkedEncryptor;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.crypto.UnwrappedBlobContext;
import org.linagora.linshare.storage.encryption.format.ChunkLayout;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobFormat;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.KeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.VaultKeyEncryptionService;
import org.linagora.linshare.storage.encryption.key.WrappedKey;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

class KekRotatorTest {

	private static final String BUCKET = "bucket-1";

	private HttpServer vaultServer;

	@AfterEach
	void stopVaultServer() {
		if (vaultServer != null) {
			vaultServer.stop(0);
		}
	}

	private KeyEncryptionService localKeyService(String keyId) {
		byte[] masterKey = new byte[32];
		new SecureRandom().nextBytes(masterKey);
		return new LocalKeyEncryptionService(masterKey, keyId);
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random(23).nextBytes(bytes);
		return bytes;
	}

	private FileMetaData encryptedMetadata(InMemoryFileDataStore store, KeyEncryptionService keyService,
			byte[] plaintext, String uuid) throws IOException {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "f.bin");
		metadata.setUuid(uuid);
		metadata.setBucketUuid(BUCKET);
		EncryptionParameters params = new EncryptionParameters(16, 64, 512);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		new ChunkedEncryptor(keyService, params).encrypt(new ByteArrayInputStream(plaintext), plaintext.length,
				uuid.getBytes(StandardCharsets.UTF_8), buffer);
		store.putRaw(BUCKET, uuid, buffer.toByteArray());
		return metadata;
	}

	@Test
	void missingBlobReturnsMissing() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KekRotator rotator = new KekRotator(store, "old", localKeyService("old"), localKeyService("new"));
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream", 10L, "f.bin");
		metadata.setUuid("does-not-exist");
		metadata.setBucketUuid(BUCKET);

		assertEquals(RotationOutcome.MISSING, rotator.rotate(metadata, "new"));
	}

	@Test
	void freshRotationRewrapsHeaderOnlyAndLeavesChunkBytesUntouched() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(16 * 5 + 3);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");
		byte[] beforeRotation = store.rawBytes(BUCKET, "doc-1");
		EncryptedBlobHeader headerBefore = EncryptedBlobFormat
				.readHeader(new ByteArrayInputStream(beforeRotation));
		long headerLength = ChunkLayout.of(headerBefore).headerTotalLength();

		KeyEncryptionService newKeyService = localKeyService("new-kek");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, newKeyService);

		RotationOutcome outcome = rotator.rotate(metadata, "new-kek");

		assertEquals(RotationOutcome.ROTATED, outcome);
		byte[] afterRotation = store.rawBytes(BUCKET, "doc-1");
		assertFalse(store.exists(tempOf(metadata)), "temp key must be cleaned up after commit");

		// Chunk bytes (everything after the header) must be byte-for-byte
		// identical — proving no chunk was decrypted/re-encrypted.
		byte[] chunksBefore = java.util.Arrays.copyOfRange(beforeRotation, (int) headerLength,
				beforeRotation.length);
		byte[] chunksAfter = java.util.Arrays.copyOfRange(afterRotation, (int) headerLength, afterRotation.length);
		assertArrayEquals(chunksBefore, chunksAfter);

		EncryptedBlobHeader headerAfter = EncryptedBlobFormat.readHeader(new ByteArrayInputStream(afterRotation));
		assertEquals("new-kek", headerAfter.getKeyId());
		assertFalse(java.util.Arrays.equals(headerBefore.getWrappedKeyBytes(), headerAfter.getWrappedKeyBytes()));

		byte[] decrypted;
		ChunkedDecryptor decryptor = new ChunkedDecryptor(newKeyService);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		decryptor.decrypt(new ByteArrayInputStream(afterRotation), "doc-1".getBytes(StandardCharsets.UTF_8), out);
		decrypted = out.toByteArray();
		assertArrayEquals(plaintext, decrypted);
	}

	@Test
	void plaintextBlobIsReportedNotEncryptedInsteadOfThrowing() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		byte[] plaintext = randomBytes(50);
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) plaintext.length, "f.bin");
		metadata.setUuid("doc-1");
		metadata.setBucketUuid(BUCKET);
		store.putRaw(BUCKET, "doc-1", plaintext);
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"), localKeyService("new-kek"));

		assertEquals(RotationOutcome.NOT_ENCRYPTED, rotator.rotate(metadata, "new-kek"));
		assertArrayEquals(plaintext, store.rawBytes(BUCKET, "doc-1"));
		assertFalse(store.exists(tempOf(metadata)));
	}

	@Test
	void blobShorterThanTheMagicIsReportedNotEncrypted() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream", 2L, "f.bin");
		metadata.setUuid("doc-1");
		metadata.setBucketUuid(BUCKET);
		store.putRaw(BUCKET, "doc-1", new byte[] { 1, 2 });
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"), localKeyService("new-kek"));

		assertEquals(RotationOutcome.NOT_ENCRYPTED, rotator.rotate(metadata, "new-kek"));
	}

	@Test
	void rotatesAThumbnailBlobWhoseSizeIsNotRecorded() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(16 * 5 + 3);
		FileMetaData documentLike = encryptedMetadata(store, oldKeyService, plaintext, "thmb-1");
		// Thumbnail metadata as the batches build it: no size, thumbnail kind.
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.THUMBNAIL_SMALL, "image/png", null);
		metadata.setUuid(documentLike.getUuid());
		metadata.setBucketUuid(BUCKET);
		KeyEncryptionService newKeyService = localKeyService("new-kek");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, newKeyService);

		assertEquals(RotationOutcome.ROTATED, rotator.rotate(metadata, "new-kek"));

		EncryptedBlobHeader header = EncryptedBlobFormat
				.readHeader(new ByteArrayInputStream(store.rawBytes(BUCKET, "thmb-1")));
		assertEquals("new-kek", header.getKeyId());
		assertEquals(RotationOutcome.ALREADY_ROTATED, rotator.rotate(metadata, "new-kek"));
	}

	private void assertLeftUntouched(InMemoryFileDataStore store, FileMetaData metadata, byte[] before) {
		assertArrayEquals(before, store.rawBytes(BUCKET, metadata.getUuid()));
		assertFalse(store.exists(tempOf(metadata)));
	}

	private FileMetaData rawBlob(InMemoryFileDataStore store, String uuid, byte[] bytes) {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream",
				(long) bytes.length, "f.bin");
		metadata.setUuid(uuid);
		metadata.setBucketUuid(BUCKET);
		store.putRaw(BUCKET, uuid, bytes);
		return metadata;
	}

	/** A valid encrypted blob with one byte of its fixed header (counted after the magic) replaced. */
	private FileMetaData blobWithPatchedHeaderByte(InMemoryFileDataStore store, int offsetAfterMagic, int value)
			throws IOException {
		FileMetaData valid = encryptedMetadata(store, localKeyService("old-kek"), randomBytes(50), "doc-1");
		byte[] bytes = store.rawBytes(BUCKET, "doc-1");
		bytes[EncryptedBlobHeader.magic().length + offsetAfterMagic] = (byte) value;
		store.putRaw(BUCKET, "doc-1", bytes);
		return valid;
	}

	@Test
	void blobUnderAnUnknownKeyIdIsReportedAndLeftUntouched() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		// From two rotations ago: neither the previous nor the current key.
		FileMetaData metadata = encryptedMetadata(store, localKeyService("kek-2024"), randomBytes(50), "doc-1");
		byte[] before = store.rawBytes(BUCKET, "doc-1");
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"),
				localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNKNOWN_KEY_ID, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, before);
	}

	@Test
	void truncatedHeaderIsReportedUnreadable() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		byte[] magic = EncryptedBlobHeader.magic();
		byte[] truncated = Arrays.copyOf(magic, magic.length + 3);
		FileMetaData metadata = rawBlob(store, "doc-1", truncated);
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"),
				localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNREADABLE_HEADER, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, truncated);
	}

	@Test
	void corruptedHeaderFieldIsReportedUnreadable() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		// The reserved flags byte, which must be zero.
		FileMetaData metadata = blobWithPatchedHeaderByte(store, 3, 0x7F);
		byte[] before = store.rawBytes(BUCKET, "doc-1");
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"),
				localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNREADABLE_HEADER, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, before);
	}

	@Test
	void negativeWrappedKeyLengthIsReportedUnreadable() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		FileMetaData metadata = encryptedMetadata(store, localKeyService("old-kek"), randomBytes(50), "doc-1");
		byte[] bytes = store.rawBytes(BUCKET, "doc-1");
		// wrappedKeyLength, the fixed header's second-to-last int.
		java.nio.ByteBuffer.wrap(bytes).putInt(EncryptedBlobHeader.FIXED_HEADER_LENGTH - 8, -1);
		store.putRaw(BUCKET, "doc-1", bytes);
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"),
				localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNREADABLE_HEADER, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, bytes);
	}

	@Test
	void unsupportedFormatVersionIsReportedUnreadable() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		// The format version byte.
		FileMetaData metadata = blobWithPatchedHeaderByte(store, 0, 0xEE);
		byte[] before = store.rawBytes(BUCKET, "doc-1");
		KekRotator rotator = new KekRotator(store, "old-kek", localKeyService("old-kek"),
				localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNREADABLE_HEADER, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, before);
	}

	@Test
	void corruptedWrappedKeyUnderThePreviousKeyIsReportedUnwrapFailed() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, randomBytes(16 * 5 + 3), "doc-1");
		byte[] original = store.rawBytes(BUCKET, "doc-1");
		EncryptedBlobHeader header = EncryptedBlobFormat.readHeader(new ByteArrayInputStream(original));
		byte[] wrappedKey = header.getWrappedKeyBytes().clone();
		wrappedKey[wrappedKey.length - 1] ^= 0x01;
		EncryptedBlobHeader damaged = new EncryptedBlobHeader(header.getFormatVersion(), header.getAlgorithmId(),
				header.getNonceSchemeId(), header.getPlaintextSize(), header.getChunkPlaintextSize(),
				header.getChunkCount(), header.getNoncePrefix(), header.getKeyId(),
				header.getReservedKeyIdCapacity(), wrappedKey, header.getReservedWrappedKeyCapacity());
		ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
		EncryptedBlobFormat.writeHeader(damaged, rebuilt);
		int headerLength = (int) ChunkLayout.of(header).headerTotalLength();
		rebuilt.write(original, headerLength, original.length - headerLength);
		byte[] before = rebuilt.toByteArray();
		store.putRaw(BUCKET, "doc-1", before);
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, localKeyService("new-kek"));

		assertEquals(RotationOutcome.UNWRAP_FAILED, rotator.rotate(metadata, "new-kek"));
		assertLeftUntouched(store, metadata, before);
	}

	@Test
	void constructorRejectsAMissingOldKeyId() {
		InMemoryFileDataStore store = new InMemoryFileDataStore();

		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new KekRotator(store, "", localKeyService("old-kek"), localKeyService("new-kek")));
	}

	private FileMetaData encryptedInAtomicStore(InMemoryAtomicFileDataStore store, KeyEncryptionService keyService,
			byte[] plaintext, String uuid) throws IOException {
		InMemoryFileDataStore scratch = new InMemoryFileDataStore();
		FileMetaData metadata = encryptedMetadata(scratch, keyService, plaintext, uuid);
		store.putRaw(BUCKET, uuid, scratch.rawBytes(BUCKET, uuid));
		return metadata;
	}

	@Test
	void blobThatCannotBeReplacedInPlaceIsSkippedBeforeAnyTempIsWritten() throws Exception {
		InMemoryAtomicFileDataStore store = new InMemoryAtomicFileDataStore();
		store.canReplace = false;
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		FileMetaData metadata = encryptedInAtomicStore(store, oldKeyService, randomBytes(50), "doc-1");
		byte[] before = store.rawBytes(BUCKET, "doc-1");
		// An orphan left by a run from before this check existed.
		store.putRaw(BUCKET, "doc-1.rewrapping", randomBytes(10));
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, localKeyService("new-kek"));

		assertEquals(RotationOutcome.STORAGE_MIGRATION_PENDING, rotator.rotate(metadata, "new-kek"));

		assertLeftUntouched(store, metadata, before);
		assertFalse(store.atomicReplaceWasCalled);
	}

	@Test
	void failedCommitRemovesTheTempAndLeavesTheOriginalUntouched() throws Exception {
		InMemoryAtomicFileDataStore store = new InMemoryAtomicFileDataStore();
		store.replaceFailure = new IOException("cannot atomically replace across backends");
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		FileMetaData metadata = encryptedInAtomicStore(store, oldKeyService, randomBytes(50), "doc-1");
		byte[] before = store.rawBytes(BUCKET, "doc-1");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, localKeyService("new-kek"));

		org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> rotator.rotate(metadata, "new-kek"));

		assertLeftUntouched(store, metadata, before);

		store.replaceFailure = null;
		assertEquals(RotationOutcome.ROTATED, rotator.rotate(metadata, "new-kek"));
	}

	@Test
	void alreadyRotatedBlobIsSkippedIdempotently() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(50);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");
		KeyEncryptionService newKeyService = localKeyService("new-kek");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, newKeyService);
		rotator.rotate(metadata, "new-kek");
		byte[] afterFirstRotation = store.rawBytes(BUCKET, "doc-1");

		RotationOutcome secondRun = rotator.rotate(metadata, "new-kek");

		assertEquals(RotationOutcome.ALREADY_ROTATED, secondRun);
		assertArrayEquals(afterFirstRotation, store.rawBytes(BUCKET, "doc-1"));
	}

	@Test
	void oversizedWrappedKeyIsReportedNotSilentlyTruncated() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(50);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");
		// Simulates a provider whose wrapped output is larger than the
		// blob's already-reserved wrapped-key capacity (default 512 bytes).
		KeyEncryptionService oversizedKeyService = new KeyEncryptionService() {
			@Override
			public WrappedKey wrap(byte[] dek) {
				return new WrappedKey("oversized-kek", new byte[600], "TEST-OVERSIZED");
			}

			@Override
			public byte[] unwrap(String keyId, byte[] wrappedKeyBytes) {
				throw new UnsupportedOperationException();
			}
		};
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, oversizedKeyService);

		RotationOutcome outcome = rotator.rotate(metadata, "oversized-kek");

		assertEquals(RotationOutcome.WRAPPED_KEY_TOO_LARGE, outcome);
		// Nothing must have been written: the original blob is untouched.
		EncryptedBlobHeader header = EncryptedBlobFormat
				.readHeader(new ByteArrayInputStream(store.rawBytes(BUCKET, "doc-1")));
		assertEquals("old-kek", header.getKeyId());
	}

	@Test
	void corruptedRewriteFailsVerificationAndLeavesOriginalUntouched() throws Exception {
		CorruptingOnTempWriteFileDataStore store = new CorruptingOnTempWriteFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(16 * 3 + 5);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");
		byte[] original = store.rawBytes(BUCKET, "doc-1");
		KeyEncryptionService newKeyService = localKeyService("new-kek");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, newKeyService);

		RotationOutcome outcome = rotator.rotate(metadata, "new-kek");

		assertEquals(RotationOutcome.VERIFICATION_FAILED, outcome);
		assertFalse(store.exists(tempOf(metadata)), "corrupted temp rewrite must be cleaned up");
		assertArrayEquals(original, store.rawBytes(BUCKET, "doc-1"));
	}

	@Test
	void commitUsesAtomicReplaceWhenBackendSupportsIt() throws Exception {
		InMemoryAtomicFileDataStore store = new InMemoryAtomicFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-kek");
		byte[] plaintext = randomBytes(50);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");
		KeyEncryptionService newKeyService = localKeyService("new-kek");
		KekRotator rotator = new KekRotator(store, "old-kek", oldKeyService, newKeyService);

		assertEquals(RotationOutcome.ROTATED, rotator.rotate(metadata, "new-kek"));
		assertTrue(store.atomicReplaceWasCalled);
	}

	@Test
	void rotatesFromLocalProviderToVaultTransit() throws Exception {
		InMemoryFileDataStore store = new InMemoryFileDataStore();
		KeyEncryptionService oldKeyService = localKeyService("old-local-kek");
		byte[] plaintext = randomBytes(16 * 4 + 9);
		FileMetaData metadata = encryptedMetadata(store, oldKeyService, plaintext, "doc-1");

		AtomicInteger requestCount = new AtomicInteger();
		vaultServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		vaultServer.createContext("/", exchange -> {
			requestCount.incrementAndGet();
			String path = exchange.getRequestURI().getPath();
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String responseJson;
			if (path.endsWith("/encrypt/prod-transit-key")) {
				String base64Plaintext = extractJsonStringField(body, "plaintext");
				responseJson = "{\"data\":{\"ciphertext\":\"vault:v1:" + base64Plaintext + "\"}}";
			} else {
				String ciphertext = extractJsonStringField(body, "ciphertext");
				responseJson = "{\"data\":{\"plaintext\":\"" + ciphertext.substring("vault:v1:".length()) + "\"}}";
			}
			byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		vaultServer.start();
		URI vaultUrl = URI.create("http://localhost:" + vaultServer.getAddress().getPort() + "/");
		KeyEncryptionService vaultKeyService = new VaultKeyEncryptionService(vaultUrl, "test-token",
				"prod-transit-key");

		KekRotator rotator = new KekRotator(store, "old-local-kek", oldKeyService, vaultKeyService);
		RotationOutcome outcome = rotator.rotate(metadata, "prod-transit-key");

		assertEquals(RotationOutcome.ROTATED, outcome);
		assertTrue(requestCount.get() > 0);

		ChunkedDecryptor decryptor = new ChunkedDecryptor(vaultKeyService);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		decryptor.decrypt(new ByteArrayInputStream(store.rawBytes(BUCKET, "doc-1")),
				"doc-1".getBytes(StandardCharsets.UTF_8), out);
		assertArrayEquals(plaintext, out.toByteArray());
	}

	private static String extractJsonStringField(String json, String field) {
		String marker = "\"" + field + "\":\"";
		int start = json.indexOf(marker) + marker.length();
		int end = json.indexOf('"', start);
		return json.substring(start, end);
	}

	private FileMetaData tempOf(FileMetaData metadata) {
		FileMetaData temp = new FileMetaData(metadata.getKind(), metadata.getMimeType(), metadata.getSize(),
				metadata.getFileName());
		temp.setUuid(metadata.getUuid() + ".rewrapping");
		temp.setBucketUuid(metadata.getBucketUuid());
		return temp;
	}

	private static class InMemoryFileDataStore implements FileDataStore {

		final Map<String, byte[]> blobs = new HashMap<>();

		static String key(String container, String uuid) {
			return container + "/" + uuid;
		}

		@Override
		public void remove(FileMetaData metadata) {
			blobs.remove(key(metadata.getBucketUuid(), metadata.getUuid()));
		}

		@Override
		public FileMetaData add(ByteSource byteSource, FileMetaData metadata) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = byteSource.openStream()) {
				long written = ByteStreams.copy(in, out);
				if (written != metadata.getSize()) {
					throw new IOException(
							"Content-Length mismatch, actual: " + written + " expected: " + metadata.getSize());
				}
			}
			blobs.put(key(metadata.getBucketUuid(), metadata.getUuid()), out.toByteArray());
			return metadata;
		}

		@Override
		public ByteSource get(FileMetaData metadata) {
			return new ByteSource() {
				@Override
				public InputStream openStream() {
					byte[] bytes = blobs.get(key(metadata.getBucketUuid(), metadata.getUuid()));
					if (bytes == null) {
						throw new IllegalStateException("no such blob: " + metadata.getUuid());
					}
					return new ByteArrayInputStream(bytes);
				}
			};
		}

		@Override
		public boolean exists(FileMetaData metadata) {
			return blobs.containsKey(key(metadata.getBucketUuid(), metadata.getUuid()));
		}

		void putRaw(String container, String uuid, byte[] bytes) {
			blobs.put(key(container, uuid), bytes);
		}

		byte[] rawBytes(String container, String uuid) {
			return blobs.get(key(container, uuid));
		}
	}

	private static final class InMemoryAtomicFileDataStore extends InMemoryFileDataStore implements AtomicBlobReplace {

		boolean atomicReplaceWasCalled;

		/** What supportsAtomicReplace answers, e.g. false for a target still in the old store. */
		boolean canReplace = true;

		/** When set, atomicReplace fails with it, as a cross-backend commit would. */
		IOException replaceFailure;

		@Override
		public boolean supportsAtomicReplace(FileMetaData target) {
			return canReplace;
		}

		@Override
		public void atomicReplace(FileMetaData source, FileMetaData target) throws IOException {
			atomicReplaceWasCalled = true;
			if (replaceFailure != null) {
				throw replaceFailure;
			}
			byte[] sourceBytes = blobs.remove(key(source.getBucketUuid(), source.getUuid()));
			if (sourceBytes == null) {
				throw new IOException("no such source blob: " + source.getUuid());
			}
			blobs.put(key(target.getBucketUuid(), target.getUuid()), sourceBytes);
		}
	}

	/** Corrupts whatever gets written under a ".rewrapping" temp key, simulating a bad write. */
	private static final class CorruptingOnTempWriteFileDataStore extends InMemoryFileDataStore {

		@Override
		public FileMetaData add(ByteSource byteSource, FileMetaData metadata) throws IOException {
			FileMetaData stored = super.add(byteSource, metadata);
			if (metadata.getUuid().endsWith(".rewrapping")) {
				byte[] corrupted = rawBytes(metadata.getBucketUuid(), metadata.getUuid());
				corrupted[corrupted.length - 1] ^= 0x01;
				putRaw(metadata.getBucketUuid(), metadata.getUuid(), corrupted);
			}
			return stored;
		}
	}
}
