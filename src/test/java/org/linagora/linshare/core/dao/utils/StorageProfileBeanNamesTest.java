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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * springContext-storage-encryption.xml is loaded whatever the storage
 * profile: it defines {@code fileDataStore} itself, wrapping whatever the
 * active storage file defines as {@code rawFileDataStore}. A storage file
 * that still defines {@code fileDataStore} instead leaves the application
 * context unable to start (no {@code rawFileDataStore}) — which is what the
 * mongo-java-server override used by the CI API-test stage did, unnoticed,
 * because no unit test loads the web context or that override.
 *
 * <p>Parses the XML rather than loading Spring: the override is only copied
 * into place by the mongo-java-server Maven profile, so its relative
 * {@code <import>} doesn't resolve from here. Files are globbed, so a new
 * storage profile or override is covered automatically.
 */
class StorageProfileBeanNamesTest {

	private static final String ENCRYPTION_CONTEXT = "springContext-storage-encryption.xml";

	private static final String[] STORAGE_CONTEXT_DIRECTORIES = { "src/main/resources",
			"src/mongo-java-server/resources" };

	private static List<Path> storageProfileContexts() throws IOException {
		List<Path> contexts = new ArrayList<>();
		for (String directory : STORAGE_CONTEXT_DIRECTORIES) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(Paths.get(directory),
					"springContext-storage-*.xml")) {
				for (Path file : files) {
					if (!file.getFileName().toString().equals(ENCRYPTION_CONTEXT)) {
						contexts.add(file);
					}
				}
			}
		}
		return contexts;
	}

	/** Ids of the top-level beans only: inner beans don't define application-wide names. */
	private static Set<String> topLevelBeanIds(Path file) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
		Set<String> ids = new HashSet<>();
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element && "bean".equals(node.getLocalName())) {
				Element bean = (Element) node;
				ids.add(bean.hasAttribute("id") ? bean.getAttribute("id") : bean.getAttribute("name"));
			}
		}
		return ids;
	}

	@Test
	void everyStorageProfileDefinesRawFileDataStoreAndNotFileDataStore() throws Exception {
		List<Path> contexts = storageProfileContexts();
		assertTrue(contexts.size() >= 5, "expected the storage profiles and the mongo-java-server override, found "
				+ contexts);
		for (Path context : contexts) {
			Set<String> ids = topLevelBeanIds(context);
			assertTrue(ids.contains("rawFileDataStore"), context + " must define rawFileDataStore");
			assertFalse(ids.contains("fileDataStore"), context + " must not define fileDataStore: "
					+ ENCRYPTION_CONTEXT + " defines it on top of rawFileDataStore");
		}
	}

	@Test
	void theEncryptionContextDefinesFileDataStore() throws Exception {
		assertTrue(topLevelBeanIds(Paths.get("src/main/resources", ENCRYPTION_CONTEXT)).contains("fileDataStore"));
	}
}
