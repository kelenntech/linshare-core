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

public enum RotationOutcome {

	ROTATED,

	ALREADY_ROTATED,

	MISSING,

	/**
	 * The blob is still legacy plaintext (not yet migrated), so there is no
	 * wrapped key to rotate. Left untouched; the migration batch owns it.
	 */
	NOT_ENCRYPTED,

	/** The new wrapped key/key id doesn't fit within this blob's reserved header capacity. */
	WRAPPED_KEY_TOO_LARGE,

	VERIFICATION_FAILED,

	/** The header's key id is neither the previous nor the current key (e.g. from two rotations ago). */
	UNKNOWN_KEY_ID,

	/** The blob has the LSE1 magic but its header is corrupt, truncated or of an unsupported version. */
	UNREADABLE_HEADER,

	/**
	 * The header names the previous key but unwrapping failed: either this
	 * blob's wrapped key is corrupted, or the previous key material/key
	 * service is unavailable (the two can't be told apart from here).
	 */
	UNWRAP_FAILED,

	/**
	 * The storage backend can't replace this blob in place yet; in practice
	 * it still lives in the old store during the gridfs-to-jcloud storage
	 * migration. Left untouched; handled once the storage migration has
	 * moved it.
	 */
	STORAGE_MIGRATION_PENDING
}
