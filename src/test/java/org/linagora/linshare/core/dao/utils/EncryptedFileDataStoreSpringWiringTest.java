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
package org.linagora.linshare.core.dao.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linagora.linshare.core.batches.impl.RotateKekBatchImpl;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.FileSystemJcloudFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.RotationOutcome;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;
import org.linagora.linshare.storage.encryption.crypto.EncryptionParameters;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;
import org.linagora.linshare.storage.encryption.key.LocalKeyEncryptionService;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

/**
 * Loads the real {@code springContext-storage-encryption.xml} (with the real
 * {@code linshare-default.properties} underneath) rather than calling the
 * factory's setters by hand, which is what
 * {@link EncryptedFileDataStoreFactoryTest} does. The previous-key setters
 * existed and were unit-tested while nothing in the XML ever injected them,
 * so in the deployed application {@code previous-key-id} was silently
 * ignored: a store that only knew the new key, every existing blob failing
 * with "Unknown key id", and the rotation batch never running.
 */
class EncryptedFileDataStoreSpringWiringTest {

	private static final EncryptionParameters PARAMS = new EncryptionParameters(64 * 1024,
			EncryptedBlobHeader.DEFAULT_KEY_ID_CAPACITY, EncryptedBlobHeader.DEFAULT_WRAPPED_KEY_CAPACITY);

	private FileDataStore newRawStore(Path directory) {
		return new FileSystemJcloudFileDataStoreImpl(ImmutableSet.of(new SLF4JLoggingModule()), new Properties(),
				"test-bucket", directory.toString());
	}

	private byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random(31).nextBytes(bytes);
		return bytes;
	}

	/** The application's own storage-encryption XML, fed by the shipped defaults plus these overrides. */
	private GenericApplicationContext loadContext(FileDataStore rawFileDataStore, Map<String, Object> overrides) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", overrides));
		PropertySourcesPlaceholderConfigurer placeholders = new PropertySourcesPlaceholderConfigurer();
		placeholders.setLocation(new ClassPathResource("linshare-default.properties"));
		placeholders.setEnvironment(context.getEnvironment());
		context.addBeanFactoryPostProcessor(placeholders);
		context.getBeanFactory().registerSingleton("rawFileDataStore", rawFileDataStore);
		new XmlBeanDefinitionReader(context)
				.loadBeanDefinitions(new ClassPathResource("springContext-storage-encryption.xml"));
		context.refresh();
		return context;
	}

	private Map<String, Object> encryptionProperties(Path currentKeyFile) {
		Map<String, Object> properties = new HashMap<>();
		properties.put("linshare.documents.encryption.write-enabled", "true");
		properties.put("linshare.documents.encryption.read-enabled", "true");
		properties.put("linshare.documents.encryption.key-provider", "local");
		properties.put("linshare.documents.encryption.key-id", "kek-2026");
		properties.put("linshare.documents.encryption.local.master-key-file", currentKeyFile.toString());
		return properties;
	}

	@SuppressWarnings("unchecked")
	private RotateKekBatchImpl rotationBatchOver(FileDataStore fileDataStore) {
		return new RotateKekBatchImpl(mock(AccountRepository.class), mock(DocumentRepository.class), fileDataStore);
	}

	@Test
	void previousKeySettingsReachTheFactoryKeepOldBlobsReadableAndEnableTheRotationBatch(@TempDir Path tempDir)
			throws Exception {
		Path oldKeyFile = tempDir.resolve("kek-2025.bin");
		byte[] oldKey = randomKey();
		Files.write(oldKeyFile, oldKey);
		Path newKeyFile = tempDir.resolve("kek-2026.bin");
		Files.write(newKeyFile, randomKey());
		FileDataStore rawStore = newRawStore(tempDir.resolve("data"));

		// A blob already stored under the previous key, as every existing one is.
		EncryptedFileDataStoreImpl previousKeyOnlyStore = new EncryptedFileDataStoreImpl(rawStore,
				new LocalKeyEncryptionService(oldKey, "kek-2025"), PARAMS, true, true, true, "kek-2025", null);
		byte[] plaintext = randomBytes(1000);
		FileMetaData existing = previousKeyOnlyStore.add(ByteSource.wrap(plaintext),
				new FileMetaData(FileMetaDataKind.DATA, "application/octet-stream", (long) plaintext.length, "f.bin"));

		Map<String, Object> properties = encryptionProperties(newKeyFile);
		properties.put("linshare.documents.encryption.previous-key-id", "kek-2025");
		properties.put("linshare.documents.encryption.local.previous-master-key-file", oldKeyFile.toString());
		try (GenericApplicationContext context = loadContext(rawStore, properties)) {
			FileDataStore fileDataStore = context.getBean("fileDataStore", FileDataStore.class);

			assertTrue(fileDataStore instanceof EncryptedFileDataStoreImpl);
			EncryptedFileDataStoreImpl store = (EncryptedFileDataStoreImpl) fileDataStore;
			assertTrue(store.isRotationConfigured(),
					"previous-key-id was set but never reached EncryptedFileDataStoreFactory");

			// Without the previous key this fails with "Unknown key id: kek-2025".
			try (InputStream in = store.get(existing).openStream()) {
				assertArrayEquals(plaintext, ByteStreams.toByteArray(in));
			}

			RotateKekBatchImpl batch = rotationBatchOver(fileDataStore);
			assertTrue(batch.needToRun(), "the rotation batch must run once a previous key is configured");

			assertEquals(RotationOutcome.ROTATED, store.rotateKek(existing));
			try (InputStream in = store.get(existing).openStream()) {
				assertArrayEquals(plaintext, ByteStreams.toByteArray(in));
			}
		}
	}

	@Test
	void withoutAPreviousKeyRotationIsNotConfiguredAndTheBatchDoesNotRun(@TempDir Path tempDir) throws Exception {
		Path newKeyFile = tempDir.resolve("kek-2026.bin");
		Files.write(newKeyFile, randomKey());
		FileDataStore rawStore = newRawStore(tempDir.resolve("data"));

		// The three previous-* properties are left to their (empty) shipped defaults.
		try (GenericApplicationContext context = loadContext(rawStore, encryptionProperties(newKeyFile))) {
			FileDataStore fileDataStore = context.getBean("fileDataStore", FileDataStore.class);

			assertTrue(fileDataStore instanceof EncryptedFileDataStoreImpl);
			assertFalse(((EncryptedFileDataStoreImpl) fileDataStore).isRotationConfigured());
			assertFalse(rotationBatchOver(fileDataStore).needToRun());
		}
	}

	@Test
	void encryptionDisabledByDefaultStillYieldsTheRawStore(@TempDir Path tempDir) throws Exception {
		FileDataStore rawStore = newRawStore(tempDir.resolve("data"));

		try (GenericApplicationContext context = loadContext(rawStore, new HashMap<>())) {
			assertEquals(rawStore, context.getBean("fileDataStore", FileDataStore.class));
		}
	}
}
