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
package org.linagora.linshare.storage.encryption.key;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.linagora.linshare.storage.encryption.exception.EncryptedBlobKeyException;
import org.linagora.linshare.storage.encryption.format.EncryptedBlobHeader;

class RotatableKeyEncryptionServiceTest {

	private byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private byte[] randomDek() {
		byte[] dek = new byte[EncryptedBlobHeader.DEK_LENGTH_BYTES];
		new SecureRandom().nextBytes(dek);
		return dek;
	}

	@Test
	void wrapAlwaysUsesCurrent() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		LocalKeyEncryptionService previous = new LocalKeyEncryptionService(randomKey(), "previous-kek");
		RotatableKeyEncryptionService rotatable = new RotatableKeyEncryptionService("current-kek", current,
				"previous-kek", previous);

		WrappedKey wrapped = rotatable.wrap(randomDek());

		assertEquals("current-kek", wrapped.getKeyId());
	}

	@Test
	void unwrapRoutesToCurrentForCurrentKeyId() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		LocalKeyEncryptionService previous = new LocalKeyEncryptionService(randomKey(), "previous-kek");
		RotatableKeyEncryptionService rotatable = new RotatableKeyEncryptionService("current-kek", current,
				"previous-kek", previous);
		byte[] dek = randomDek();
		WrappedKey wrapped = current.wrap(dek);

		byte[] unwrapped = rotatable.unwrap(wrapped.getKeyId(), wrapped.getWrappedKeyBytes());

		assertArrayEquals(dek, unwrapped);
	}

	@Test
	void unwrapRoutesToPreviousForPreviousKeyId() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		LocalKeyEncryptionService previous = new LocalKeyEncryptionService(randomKey(), "previous-kek");
		RotatableKeyEncryptionService rotatable = new RotatableKeyEncryptionService("current-kek", current,
				"previous-kek", previous);
		byte[] dek = randomDek();
		WrappedKey wrapped = previous.wrap(dek);

		byte[] unwrapped = rotatable.unwrap(wrapped.getKeyId(), wrapped.getWrappedKeyBytes());

		assertArrayEquals(dek, unwrapped);
	}

	@Test
	void unwrapThrowsForUnknownKeyIdWhenPreviousConfigured() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		LocalKeyEncryptionService previous = new LocalKeyEncryptionService(randomKey(), "previous-kek");
		RotatableKeyEncryptionService rotatable = new RotatableKeyEncryptionService("current-kek", current,
				"previous-kek", previous);

		assertThrows(EncryptedBlobKeyException.class, () -> rotatable.unwrap("some-other-kek", new byte[32]));
	}

	@Test
	void unwrapThrowsForAnyOtherKeyIdWhenNoPreviousConfigured() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		RotatableKeyEncryptionService rotatable = new RotatableKeyEncryptionService("current-kek", current, null,
				null);

		assertThrows(EncryptedBlobKeyException.class, () -> rotatable.unwrap("previous-kek", new byte[32]));
	}

	@Test
	void constructorRejectsMismatchedPreviousKeyIdAndService() {
		LocalKeyEncryptionService current = new LocalKeyEncryptionService(randomKey(), "current-kek");
		LocalKeyEncryptionService previous = new LocalKeyEncryptionService(randomKey(), "previous-kek");

		assertThrows(EncryptedBlobKeyException.class,
				() -> new RotatableKeyEncryptionService("current-kek", current, "previous-kek", null));
		assertThrows(EncryptedBlobKeyException.class,
				() -> new RotatableKeyEncryptionService("current-kek", current, null, previous));
	}
}
