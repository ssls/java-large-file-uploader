package com.am.jlfu.fileuploader.logic;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.am.jlfu.fileuploader.exception.InvalidCrcException;
import com.am.jlfu.fileuploader.json.CRCResult;
import com.am.jlfu.fileuploader.json.FileStateJson;
import com.am.jlfu.fileuploader.json.FileStateJsonBase;
import com.am.jlfu.fileuploader.json.InitializationConfiguration;
import com.am.jlfu.fileuploader.limiter.RateLimiterConfigurationManager;
import com.am.jlfu.fileuploader.utils.CRCHelper;
import com.am.jlfu.fileuploader.utils.UploadLockMap;
import com.am.jlfu.staticstate.StaticStateDirectoryManager;
import com.am.jlfu.staticstate.StaticStateIdentifierManager;
import com.am.jlfu.staticstate.StaticStateManager;
import com.am.jlfu.staticstate.entities.StaticFileState;
import com.am.jlfu.staticstate.entities.StaticStatePersistedOnFileSystemEntity;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;



@Component
public class UploadProcessor {

	private static final Logger log = LoggerFactory.getLogger(UploadProcessor.class);

	@Autowired
	CRCHelper crcHelper;

	@Autowired
	RateLimiterConfigurationManager uploadProcessingConfigurationManager;

	@Autowired
	StaticStateManager<StaticStatePersistedOnFileSystemEntity> staticStateManager;

	@Autowired
	StaticStateIdentifierManager staticStateIdentifierManager;

	@Autowired
	StaticStateDirectoryManager staticStateDirectoryManager;

	@Autowired
	UploadLockMap lockMap;

	public static final int SIZE_OF_FIRST_CHUNK_VALIDATION = 8192;

	/** Executor service for closing streams */
	private ListeningExecutorService streamCloserExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(20));

	/**
	 * Size of a slice <br>
	 * Default to 10MB.
	 */
	public static final long sliceSizeInBytes = 10485760; // 10mb



	public InitializationConfiguration getConfig() {
		InitializationConfiguration config = new InitializationConfiguration();
		StaticStatePersistedOnFileSystemEntity entity = staticStateManager.getEntity();

		if (entity != null) {

			// order files by age
			Ordering<String> ordering = Ordering.from(new Comparator<StaticFileState>() {

				@Override
				public int compare(StaticFileState o1, StaticFileState o2) {
					int compareTo = o1.getStaticFileStateJson().getCreationDate().compareTo(o2.getStaticFileStateJson().getCreationDate());
					return compareTo != 0 ? compareTo : 1;
				}

			}).onResultOf(Functions.forMap(entity.getFileStates()));

			// apply comparator
			ImmutableSortedMap<String, StaticFileState> sortedMap = ImmutableSortedMap.copyOf(entity.getFileStates(), ordering);

			// fill pending files from static state
			config.setPendingFiles(Maps.transformValues(sortedMap, new Function<StaticFileState, FileStateJson>() {

				@Override
				public FileStateJson apply(StaticFileState value) {
					return getFileStateJson(value, true);
				}


			}));

			// reset paused attribute
			for (String fileId : sortedMap.keySet()) {
				uploadProcessingConfigurationManager.getUploadProcessingConfiguration(fileId).setPaused(false);
			}
		}

		// fill configuration
		config.setInByte(sliceSizeInBytes);

		return config;
	}


	private FileStateJson getFileStateJson(StaticFileState value, boolean withCrcOfFirstChunk) {
		File file = new File(value.getAbsoluteFullPathOfUploadedFile());
		Long fileSize = file.length();

		FileStateJsonBase staticFileStateJson = value.getStaticFileStateJson();
		FileStateJson fileStateJson = new FileStateJson();
		fileStateJson.setFileComplete(staticFileStateJson.getCrcedBytes().equals(staticFileStateJson.getOriginalFileSizeInBytes()));
		fileStateJson.setFileCompletionInBytes(fileSize);
		fileStateJson.setOriginalFileName(staticFileStateJson.getOriginalFileName());
		fileStateJson.setOriginalFileSizeInBytes(staticFileStateJson.getOriginalFileSizeInBytes());
		fileStateJson.setRateInKiloBytes(staticFileStateJson.getRateInKiloBytes());
		fileStateJson.setCrcedBytes(staticFileStateJson.getCrcedBytes());
		fileStateJson.setCreationDate(staticFileStateJson.getCreationDate());
		if (withCrcOfFirstChunk) {
			try {
				fileStateJson.setFirstChunkCrc(getCRCOfFirstChunk(file));
			}
			catch (IOException e) {
				log.error("Cannot calculate the first chunk crc of file " + file, e);
			}
		}
		log.debug("returning pending file " + fileStateJson.getOriginalFileName() + " with target size " +
				fileStateJson.getOriginalFileSizeInBytes() + " out of " + fileSize + " completed which includes " +
				fileStateJson.getCrcedBytes() + " bytes validated and " + (fileSize - fileStateJson.getCrcedBytes()) + " unvalidated.");

		return fileStateJson;
	}


	public String prepareUpload(Long size, String fileName)
			throws IOException {

		// retrieve model
		StaticStatePersistedOnFileSystemEntity model = staticStateManager.getEntity();

		// extract the extension of the filename
		String fileExtension = extractExtensionOfFileName(fileName);

		// create a new file for it
		String fileId = UUID.randomUUID().toString();
		File file = new File(staticStateDirectoryManager.getUUIDFileParent(), fileId + fileExtension);
		file.createNewFile();
		StaticFileState fileState = new StaticFileState();
		FileStateJsonBase jsonFileState = new FileStateJsonBase();
		fileState.setStaticFileStateJson(jsonFileState);
		fileState.setAbsoluteFullPathOfUploadedFile(file.getAbsolutePath());
		model.getFileStates().put(fileId, fileState);

		// add info to the state
		jsonFileState.setOriginalFileName(fileName);
		jsonFileState.setOriginalFileSizeInBytes(size);
		jsonFileState.setCreationDate(new Date());

		// write the state
		staticStateManager.processEntityTreatment(model);

		// and returns the file identifier
		log.debug("File prepared for client " + staticStateIdentifierManager.getIdentifier() + " at path " + file.getAbsolutePath());
		return fileId;

	}


	private String extractExtensionOfFileName(String fileName) {
		String[] split = fileName.split("\\.");
		String fileExtension = "";
		if (split.length > 1) {
			if (split.length > 0) {
				fileExtension = '.' + split[split.length - 1];
			}
		}
		return fileExtension;
	}


	private void closeStreamForFiles(String... fileIds) {

		// submit them
		Map<String, ListenableFuture<?>> submits = Maps.newHashMap();
		for (final String fileId : fileIds) {

			log.debug("asking for stream close for file with id " + fileId);

			// ask for the stream to close
			boolean needToCloseStream = uploadProcessingConfigurationManager.markRequestHasShallBeCancelled(fileId);

			if (needToCloseStream) {
				log.debug("waiting for the stream to be closed for " + fileId);

				// then wait for the stream to be closed
				submits.put(fileId, streamCloserExecutor.submit(new Runnable() {

					@Override
					public void run() {
						DateTime start = new DateTime();
						while (uploadProcessingConfigurationManager.requestHasToBeCancelled(fileId)) {
							if (new DateTime().isAfter(start.plusMillis(1000))) {
								log.debug("cannot confirm that file stream is closed for " + fileId);
								return;
							}
							try {
								Thread.sleep(10);
							}
							catch (InterruptedException e) {
								throw new RuntimeException(e);
							}
						}
						log.debug("file stream has been closed for " + fileId);
					}
				}));
			}
		}

		Futures.getUnchecked(Futures.allAsList(submits.values()));

	}


	public void clearFile(String fileId)
			throws InterruptedException, ExecutionException, TimeoutException {

		// ask for the stream to be closed
		closeStreamForFiles(fileId);

		// then delete
		staticStateManager.clearFile(fileId);
	}


	public void clearAll()
			throws InterruptedException, ExecutionException, TimeoutException {

		// close any pending stream before clearing the file
		closeStreamForFiles(staticStateManager.getEntity().getFileStates().keySet().toArray(new String[] {}));

		// then clear everything
		staticStateManager.clear();
	}


	public Float getProgress(String fileId)
			throws FileNotFoundException {


		// get the file
		StaticStatePersistedOnFileSystemEntity model = staticStateManager.getEntity();
		StaticFileState fileState = model.getFileStates().get(fileId);
		if (fileState == null) {
			throw new FileNotFoundException("File with id " + fileId + " not found");
		}
		File file = new File(fileState.getAbsoluteFullPathOfUploadedFile());

		// compare size o6f the file to the expected size
		Float progress = getProgress(file.length(), fileState.getStaticFileStateJson().getOriginalFileSizeInBytes()).floatValue();

		log.debug("progress for file " + fileId + ": " + progress);
		return progress;
	}


	Double getProgress(Long currentSize, Long expectedSize) {
		double percent = currentSize.doubleValue() / expectedSize.doubleValue() * 100d;
		if (percent == 100 && expectedSize - currentSize != 0) {
			percent = 99.99d;
		}
		return percent;
	}


	public Long getUploadStat(String fileId) {
		return uploadProcessingConfigurationManager.getUploadState(fileId);
	}


	public void setUploadRate(String fileId, Long rate) {

		// set the rate
		uploadProcessingConfigurationManager.assignRateToRequest(fileId, rate);

		// save it for the file with this file id
		StaticStatePersistedOnFileSystemEntity entity = staticStateManager.getEntity();
		entity.getFileStates().get(fileId).getStaticFileStateJson().setRateInKiloBytes(rate);

		// persist changes
		staticStateManager.processEntityTreatment(entity);
	}


	public void pauseFile(String fileId) {

		// specify as paused
		uploadProcessingConfigurationManager.pause(fileId);

		// close stream
		closeStreamForFiles(fileId);

	}


	public FileStateJson resumeFile(String fileId) {

		// set as resumed
		uploadProcessingConfigurationManager.resume(fileId);

		// and return some information about it
		return getFileStateJson(staticStateManager.getEntity().getFileStates().get(fileId), false);
	}


	public FileStateJson getCRCOfFirstChunk(String fileId)
			throws IOException {
		log.debug("on the fly resume for " + fileId + ". retrieving information of server.");
		return getFileStateJson(staticStateManager.getEntity().getFileStates().get(fileId), true);

	}


	public CRCResult getCRCOfFirstChunk(File file)
			throws IOException {
		log.debug("processing crc validation of first chunk for " + file.getName());

		// if the file does not exist, there is an issue!
		if (!file.exists()) {
			throw new FileNotFoundException("File " + file + " not found");
		}

		// extract from the file input stream to get the crc of the same part:
		byte[] b = new byte[(int) Math.min(file.length(), SIZE_OF_FIRST_CHUNK_VALIDATION)];
		FileInputStream fileInputStream = new FileInputStream(file);
		IOUtils.read(fileInputStream, b);
		ByteArrayInputStream byteInputStream = new ByteArrayInputStream(b);
		CRCResult bufferedCrc = crcHelper.getBufferedCrc(byteInputStream);
		IOUtils.closeQuietly(fileInputStream);
		IOUtils.closeQuietly(byteInputStream);

		// if the size is under value, show warning
		if (bufferedCrc.getTotalRead() < SIZE_OF_FIRST_CHUNK_VALIDATION) {
			log.debug("First chunk was smaller than " + SIZE_OF_FIRST_CHUNK_VALIDATION + " bytes.");
		}

		// return it
		return bufferedCrc;
	}


	public void verifyCrcOfUncheckedPart(String fileId, String inputCrc)
			throws IOException, InvalidCrcException {
		log.debug("validating the bytes that have not been validated from the previous interrupted upload for file " +
				fileId);

		// get entity
		StaticStatePersistedOnFileSystemEntity model = staticStateManager.getEntity();

		// get the file
		StaticFileState fileState = model.getFileStates().get(fileId);
		if (fileState == null) {
			throw new FileNotFoundException("File with id " + fileId + " not found");
		}
		File file = new File(fileState.getAbsoluteFullPathOfUploadedFile());


		// if the file does not exist, there is an issue!
		if (!file.exists()) {
			throw new FileNotFoundException("File with id " + fileId + " not found");
		}

		// open the file stream
		FileInputStream fileInputStream = new FileInputStream(file);
		// skip the crced part
		fileInputStream.skip(fileState.getStaticFileStateJson().getCrcedBytes());

		// read the crc
		final CRCResult fileCrc = crcHelper.getBufferedCrc(fileInputStream);
		IOUtils.closeQuietly(fileInputStream);

		// compare them
		log.debug("validating chunk crc " + fileCrc.getCrcAsString() + " against " + inputCrc);

		// if not equal, we have an issue:
		if (!fileCrc.getCrcAsString().equals(inputCrc)) {
			log.debug("invalid crc ... now truncating file to match validated bytes " + fileState.getStaticFileStateJson().getCrcedBytes());

			// we are just sure now that the file before the crc validated is actually valid, and
			// after that, it seems it is not
			// so we get the file and remove everything after that crc validation so that user can
			// resume the fileupload from there.

			// truncate the file
			RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rwd");
			randomAccessFile.setLength(fileState.getStaticFileStateJson().getCrcedBytes());
			randomAccessFile.close();

			// throw the exception
			throw new InvalidCrcException(fileCrc.getCrcAsString(), inputCrc);
		}
		// if correct, we can add these bytes as validated inside the file
		else {
			staticStateManager.setCrcBytesValidated(staticStateIdentifierManager.getIdentifier(), fileId,
					fileCrc.getTotalRead());
		}

	}


	private void showDif(byte[] a, byte[] b) {

		log.debug("comparing " + a + " to " + b);
		log.debug("size: " + a.length + " " + b.length);
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			if (!Byte.valueOf(a[i]).equals(Byte.valueOf(b[i]))) {
				log.debug("different byte at index " + i + " : " + Byte.valueOf(a[i]) + " " + Byte.valueOf(b[i]));
			}
		}
		if (a.length != b.length) {
			log.debug("arrays do not have a similar size so i was impossible to compare " + Math.abs(a.length - b.length) + " bytes.");
		}

	}


}