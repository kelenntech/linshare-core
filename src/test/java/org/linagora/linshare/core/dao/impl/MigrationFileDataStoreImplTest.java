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
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.core.dao.AtomicBlobReplace;
import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.objects.FileMetaData;

class MigrationFileDataStoreImplTest {

	private interface AtomicFileDataStore extends FileDataStore, AtomicBlobReplace {
	}

	private FileMetaData metadata(String uuid) {
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, "text/plain", 5L, "f.txt");
		metadata.setUuid(uuid);
		return metadata;
	}

	@Test
	void bothInNewStoreForwardsToNewStore() throws IOException {
		AtomicFileDataStore newStore = mock(AtomicFileDataStore.class);
		AtomicFileDataStore oldStore = mock(AtomicFileDataStore.class);
		FileMetaData source = metadata("source-uuid");
		FileMetaData target = metadata("target-uuid");
		when(newStore.exists(source)).thenReturn(true);
		when(newStore.exists(target)).thenReturn(true);
		MigrationFileDataStoreImpl migrationStore = new MigrationFileDataStoreImpl(newStore, oldStore);

		migrationStore.atomicReplace(source, target);

		verify(newStore).atomicReplace(source, target);
		verifyNoInteractions(oldStore);
	}

	@Test
	void bothInOldStoreForwardsToOldStore() throws IOException {
		AtomicFileDataStore newStore = mock(AtomicFileDataStore.class);
		AtomicFileDataStore oldStore = mock(AtomicFileDataStore.class);
		FileMetaData source = metadata("source-uuid");
		FileMetaData target = metadata("target-uuid");
		when(newStore.exists(source)).thenReturn(false);
		when(newStore.exists(target)).thenReturn(false);
		MigrationFileDataStoreImpl migrationStore = new MigrationFileDataStoreImpl(newStore, oldStore);

		migrationStore.atomicReplace(source, target);

		verify(oldStore).atomicReplace(source, target);
	}

	@Test
	void crossBackendReplaceThrowsInsteadOfSilentlyFallingBack() {
		// The temp/source blob always lands in newDataStore, but the real
		// blob it replaces may still be sitting only in oldDataStore while a
		// gridfs-to-jcloud storage migration is incomplete. No single-backend
		// primitive can span both, so this must fail loudly (a checked
		// IOException, caught per-document by the migration batch) rather
		// than attempt an unsafe cross-backend copy.
		FileDataStore newStore = mock(FileDataStore.class);
		FileDataStore oldStore = mock(FileDataStore.class);
		FileMetaData source = metadata("source-uuid");
		FileMetaData target = metadata("target-uuid");
		when(newStore.exists(source)).thenReturn(true);
		when(newStore.exists(target)).thenReturn(false);
		MigrationFileDataStoreImpl migrationStore = new MigrationFileDataStoreImpl(newStore, oldStore);

		assertThrows(IOException.class, () -> migrationStore.atomicReplace(source, target));
	}

	@Test
	void throwsWhenSelectedBackendDoesNotSupportAtomicReplace() {
		FileDataStore newStore = mock(FileDataStore.class);
		FileDataStore oldStore = mock(FileDataStore.class);
		FileMetaData source = metadata("source-uuid");
		FileMetaData target = metadata("target-uuid");
		when(newStore.exists(source)).thenReturn(true);
		when(newStore.exists(target)).thenReturn(true);
		MigrationFileDataStoreImpl migrationStore = new MigrationFileDataStoreImpl(newStore, oldStore);

		assertThrows(IOException.class, () -> migrationStore.atomicReplace(source, target));
	}
}
