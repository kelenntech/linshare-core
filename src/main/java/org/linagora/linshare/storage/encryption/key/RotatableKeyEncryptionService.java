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

import org.linagora.linshare.storage.encryption.exception.EncryptedBlobKeyException;

/**
 * Keeps blobs wrapped under either of two keys readable across a KEK
 * rotation window: {@link #wrap} always targets the current key, but
 * {@link #unwrap} routes explicitly to whichever of {@code current}/
 * {@code previous} matches the blob's own {@code keyId} — never a
 * try-current-then-fall-back-to-previous approach, which would risk
 * mistaking a genuine corruption/tamper failure in {@code current} for
 * "must be the old key" and reporting the wrong root cause.
 */
public final class RotatableKeyEncryptionService implements KeyEncryptionService {

	private final String currentKeyId;

	private final KeyEncryptionService current;

	private final String previousKeyId;

	private final KeyEncryptionService previous;

	public RotatableKeyEncryptionService(String currentKeyId, KeyEncryptionService current, String previousKeyId,
			KeyEncryptionService previous) {
		if (currentKeyId == null || currentKeyId.isEmpty() || current == null) {
			throw new EncryptedBlobKeyException("currentKeyId and current must not be null or empty");
		}
		if ((previousKeyId == null || previousKeyId.isEmpty()) != (previous == null)) {
			throw new EncryptedBlobKeyException("previousKeyId and previous must both be set or both be null");
		}
		this.currentKeyId = currentKeyId;
		this.current = current;
		this.previousKeyId = previousKeyId;
		this.previous = previous;
	}

	@Override
	public WrappedKey wrap(byte[] dek) {
		return current.wrap(dek);
	}

	@Override
	public byte[] unwrap(String keyId, byte[] wrappedKeyBytes) {
		if (currentKeyId.equals(keyId)) {
			return current.unwrap(keyId, wrappedKeyBytes);
		}
		if (previous != null && previousKeyId.equals(keyId)) {
			return previous.unwrap(keyId, wrappedKeyBytes);
		}
		throw new EncryptedBlobKeyException("Unknown key id: " + keyId);
	}
}
