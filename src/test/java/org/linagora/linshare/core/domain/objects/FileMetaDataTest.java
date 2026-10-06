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
package org.linagora.linshare.core.domain.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.constants.ThumbnailType;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.entities.Thumbnail;

class FileMetaDataTest {

	private Document document() {
		Document document = new Document();
		document.setUuid("doc-1");
		document.setBucketUuid("bucket-1");
		document.setSize(5L);
		return document;
	}

	private void addThumbnails(Document document, ThumbnailType... types) {
		Map<ThumbnailType, Thumbnail> thumbnails = new EnumMap<>(ThumbnailType.class);
		for (ThumbnailType type : types) {
			thumbnails.put(type, new Thumbnail("thmb-" + type.name().toLowerCase(), type, document));
		}
		document.setThumbnails(thumbnails);
		document.setHasThumbnail(true);
	}

	@Test
	void documentWithoutThumbnailsHasNoThumbnailBlobs() {
		// hasThumbnail and thumbnails left null, as on a bare Document().
		assertTrue(FileMetaData.thumbnailsOf(document()).isEmpty());
	}

	@Test
	void listsEveryThumbnailTypeTheDocumentHas() {
		Document document = document();
		addThumbnails(document, ThumbnailType.SMALL, ThumbnailType.MEDIUM, ThumbnailType.LARGE);

		List<FileMetaData> thumbnails = FileMetaData.thumbnailsOf(document);

		assertEquals(3, thumbnails.size());
		Map<FileMetaDataKind, String> uuidByKind = thumbnails.stream()
				.collect(Collectors.toMap(FileMetaData::getKind, FileMetaData::getUuid));
		assertEquals("thmb-small", uuidByKind.get(FileMetaDataKind.THUMBNAIL_SMALL));
		assertEquals("thmb-medium", uuidByKind.get(FileMetaDataKind.THUMBNAIL_MEDIUM));
		assertEquals("thmb-large", uuidByKind.get(FileMetaDataKind.THUMBNAIL_LARGE));
	}

	@Test
	void usesTheDocumentsBucketAndNeverTheDataBlobsSize() {
		Document document = document();
		addThumbnails(document, ThumbnailType.SMALL);

		FileMetaData thumbnail = FileMetaData.thumbnailsOf(document).get(0);

		assertEquals("bucket-1", thumbnail.getBucketUuid());
		assertNull(thumbnail.getSize(), "the document's size is the DATA blob's, not the thumbnail's");
	}

	@Test
	void skipsAThumbnailTypeTheDocumentDoesNotHaveWithoutFailing() {
		Document document = document();
		addThumbnails(document, ThumbnailType.SMALL); // e.g. no PDF thumbnail

		assertEquals(1, FileMetaData.thumbnailsOf(document).size());
	}

	@Test
	void skipsAThumbnailWithoutAUuid() {
		Document document = document();
		addThumbnails(document, ThumbnailType.SMALL);
		document.getThumbnails().put(ThumbnailType.LARGE, new Thumbnail(null, ThumbnailType.LARGE, document));

		assertEquals(1, FileMetaData.thumbnailsOf(document).size());
	}

	@Test
	@SuppressWarnings("deprecation")
	void includesTheDeprecatedSingleThumbnailWhenSet() {
		Document document = document();
		document.setThmbUuid("legacy-thmb");

		List<FileMetaData> thumbnails = FileMetaData.thumbnailsOf(document);

		assertEquals(1, thumbnails.size());
		assertEquals(FileMetaDataKind.THUMBNAIL, thumbnails.get(0).getKind());
		assertEquals("legacy-thmb", thumbnails.get(0).getUuid());
	}

	@Test
	@SuppressWarnings("deprecation")
	void doesNotListTheSameBlobTwice() {
		Document document = document();
		addThumbnails(document, ThumbnailType.SMALL);
		document.setThmbUuid("thmb-small");

		assertEquals(1, FileMetaData.thumbnailsOf(document).size());
	}
}
