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
package org.linagora.linshare.core.batches.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.linagora.linshare.core.dao.FileDataStore;
import org.linagora.linshare.core.dao.impl.EncryptedFileDataStoreImpl;
import org.linagora.linshare.core.dao.impl.RotationOutcome;
import org.linagora.linshare.core.domain.constants.FileMetaDataKind;
import org.linagora.linshare.core.domain.entities.Account;
import org.linagora.linshare.core.domain.entities.Document;
import org.linagora.linshare.core.domain.objects.FileMetaData;
import org.linagora.linshare.core.exception.BatchBusinessException;
import org.linagora.linshare.core.exception.BusinessException;
import org.linagora.linshare.core.job.quartz.BatchResultContext;
import org.linagora.linshare.core.job.quartz.BatchRunContext;
import org.linagora.linshare.core.job.quartz.ResultContext;
import org.linagora.linshare.core.repository.AccountRepository;
import org.linagora.linshare.core.repository.DocumentRepository;

/**
 * Restartable, idempotent KEK rotation, mirroring
 * {@link MigrateLegacyDocumentsBatchImpl}. A no-op ({@link #needToRun()})
 * unless the configured {@code fileDataStore} is the encryption decorator
 * AND a previous key is configured — i.e. unless
 * {@code linshare.documents.encryption.previous-key-id} is set — so
 * installations that never configure rotation never pay for a scan.
 *
 * <p>Every document is re-examined on every run rather than tracked via a
 * persisted flag: {@link EncryptedFileDataStoreImpl#rotateKek} is cheap to
 * call on an already-rotated blob (a single header peek), so this sidesteps
 * needing a schema change purely to remember rotation state.
 */
public class RotateKekBatchImpl extends GenericBatchImpl {

	private final DocumentRepository documentRepository;

	private final FileDataStore fileDataStore;

	public RotateKekBatchImpl(AccountRepository<Account> accountRepository, DocumentRepository documentRepository,
			FileDataStore fileDataStore) {
		super(accountRepository);
		this.documentRepository = documentRepository;
		this.fileDataStore = fileDataStore;
	}

	@Override
	public boolean needToRun() {
		return fileDataStore instanceof EncryptedFileDataStoreImpl
				&& ((EncryptedFileDataStoreImpl) fileDataStore).isRotationConfigured();
	}

	@Override
	public List<String> getAll(BatchRunContext batchRunContext) {
		if (!needToRun()) {
			return Collections.emptyList();
		}
		List<String> entries = documentRepository.findAllIdentifiers();
		logger.info("{} document(s) will be checked for KEK rotation.", entries.size());
		return entries;
	}

	@Override
	public ResultContext execute(BatchRunContext batchRunContext, String identifier, long total, long position)
			throws BatchBusinessException, BusinessException {
		Document document = documentRepository.findByUuid(identifier);
		if (document == null) {
			return null;
		}
		ResultContext context = new BatchResultContext<Document>(document);
		context.setProcessed(false);
		if (document.getBucketUuid() == null) {
			console.logWarn(batchRunContext, total, position,
					"Document {} has no bucketUuid, skipping rotation.", identifier);
			return context;
		}

		EncryptedFileDataStoreImpl encryptedStore = (EncryptedFileDataStoreImpl) fileDataStore;
		FileMetaData metadata = new FileMetaData(FileMetaDataKind.DATA, document);
		try {
			RotationOutcome outcome = encryptedStore.rotateKek(metadata);
			console.logDebug(batchRunContext, total, position, "Document {} rotation outcome: {}", identifier,
					outcome);
			context.setProcessed(outcome == RotationOutcome.ROTATED);
		} catch (IOException e) {
			throw new BatchBusinessException(context,
					"Failed to rotate key for document " + identifier + ": " + e.getMessage());
		}
		return context;
	}

	@Override
	public void notify(BatchRunContext batchRunContext, ResultContext context, long total, long position) {
		@SuppressWarnings("unchecked")
		BatchResultContext<Document> cc = (BatchResultContext<Document>) context;
		if (Boolean.TRUE.equals(cc.getProcessed())) {
			console.logInfo(batchRunContext, total, position, "Document {} rotated to the current key.",
					cc.getResource().getUuid());
		}
	}

	@Override
	public void notifyError(BatchBusinessException exception, String identifier, long total, long position,
			BatchRunContext batchRunContext) {
		console.logError(batchRunContext, total, position, "Document {} rotation failed: {}", identifier,
				exception.getMessage());
	}
}
