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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.dao.AtomicBlobReplace;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;

class WrapperFileDataStoreImplTest {

	private FileMetaData metadata(String uuid) {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "text/plain", 5L, "f.txt");
		metadata.setUuid(uuid);
		return metadata;
	}

	private interface AtomicFileDataStore extends FileDataStore, AtomicBlobReplace {
	}

	@Test
	void forwardsAtomicReplaceWhenWrappedStoreSupportsIt() throws IOException {
		AtomicFileDataStore inner = mock(AtomicFileDataStore.class);
		WrapperFileDataStoreImpl wrapper = new WrapperFileDataStoreImpl(inner);
		FileMetaData source = metadata("source-uuid");
		FileMetaData target = metadata("target-uuid");

		wrapper.atomicReplace(source, target);

		verify(inner).atomicReplace(source, target);
	}

	@Test
	void throwsWhenWrappedStoreDoesNotSupportAtomicReplace() {
		FileDataStore inner = mock(FileDataStore.class);
		WrapperFileDataStoreImpl wrapper = new WrapperFileDataStoreImpl(inner);

		assertThrows(IOException.class, () -> wrapper.atomicReplace(metadata("source-uuid"), metadata("target-uuid")));
	}
}
