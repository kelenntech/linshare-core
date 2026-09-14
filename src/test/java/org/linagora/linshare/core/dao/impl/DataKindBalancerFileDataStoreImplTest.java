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
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.dao.AtomicBlobReplace;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;

class DataKindBalancerFileDataStoreImplTest {

	private interface AtomicFileDataStore extends FileDataStore, AtomicBlobReplace {
	}

	private FileMetaData metadata(FileMetaDataKind kind, String uuid) {
		FileMetaData metadata = new FileMetaData(kind, "text/plain", 5L, "f.txt");
		metadata.setUuid(uuid);
		return metadata;
	}

	@Test
	void routesAtomicReplaceToBigFilesStoreByTargetKind() throws IOException {
		AtomicFileDataStore bigStore = mock(AtomicFileDataStore.class);
		AtomicFileDataStore smallStore = mock(AtomicFileDataStore.class);
		DataKindBalancerFileDataStoreImpl balancer = new DataKindBalancerFileDataStoreImpl(bigStore, smallStore);
		FileMetaData source = metadata(FileMetaDataKind.DATA, "source-uuid");
		FileMetaData target = metadata(FileMetaDataKind.DATA, "target-uuid");

		balancer.atomicReplace(source, target);

		verify(bigStore).atomicReplace(source, target);
		verifyNoInteractions(smallStore);
	}

	@Test
	void routesAtomicReplaceToSmallFilesStoreByTargetKind() throws IOException {
		AtomicFileDataStore bigStore = mock(AtomicFileDataStore.class);
		AtomicFileDataStore smallStore = mock(AtomicFileDataStore.class);
		DataKindBalancerFileDataStoreImpl balancer = new DataKindBalancerFileDataStoreImpl(bigStore, smallStore);
		FileMetaData source = metadata(FileMetaDataKind.THUMBNAIL, "source-uuid");
		FileMetaData target = metadata(FileMetaDataKind.THUMBNAIL, "target-uuid");

		balancer.atomicReplace(source, target);

		verify(smallStore).atomicReplace(source, target);
		verifyNoInteractions(bigStore);
	}

	@Test
	void throwsWhenRoutedStoreDoesNotSupportAtomicReplace() {
		FileDataStore bigStore = mock(FileDataStore.class);
		FileDataStore smallStore = mock(FileDataStore.class);
		DataKindBalancerFileDataStoreImpl balancer = new DataKindBalancerFileDataStoreImpl(bigStore, smallStore);
		FileMetaData source = metadata(FileMetaDataKind.DATA, "source-uuid");
		FileMetaData target = metadata(FileMetaDataKind.DATA, "target-uuid");

		assertThrows(IOException.class, () -> balancer.atomicReplace(source, target));
	}
}
