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
package org.linagora.linshare.core.dao;

import java.io.IOException;

import org.linagora.linshare.core.domain.objects.FileMetaData;

/**
 * Optional capability a {@link FileDataStore} backend may implement when it
 * can atomically replace one physical blob with another already-stored one.
 * Migration (docs/ARCH.md 15, 22) needs this to commit a verified,
 * fully-written replacement blob without ever exposing a partially-written
 * target — a plain read-then-write "copy" cannot give that guarantee.
 */
public interface AtomicBlobReplace {

	/**
	 * Atomically makes {@code target}'s uuid contain exactly the bytes
	 * currently stored at {@code source}'s uuid, replacing whatever was
	 * previously there. On successful return, {@code source} no longer
	 * exists. A reader of {@code target} must never observe a
	 * partially-written result, whether this call succeeds, fails, or the
	 * process crashes during it.
	 *
	 * <p>{@code source} and {@code target} always share the same
	 * {@link FileMetaData#getKind()}; implementations that route by kind or
	 * by backend may use either interchangeably, and {@code target}'s
	 * {@link FileMetaData#getBucketUuid()} is the authoritative container.
	 * Throw {@link IOException} — never an unchecked exception — when the
	 * replacement cannot be performed (e.g. no backend supports it, or
	 * {@code source}/{@code target} live on different backends): callers
	 * such as the nightly encryption-migration batch only treat a checked
	 * {@link IOException} as a per-document, retryable failure.
	 */
	void atomicReplace(FileMetaData source, FileMetaData target) throws IOException;
}
