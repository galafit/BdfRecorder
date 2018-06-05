package com.biorecorder;

import com.biorecorder.bdfrecorder.*;

import com.biorecorder.dataformat.DataListener;
import com.biorecorder.dataformat.DataConfig;
import com.biorecorder.edflib.DataFormat;
import com.biorecorder.edflib.EdfHeader;
import com.biorecorder.edflib.EdfWriter;
import com.sun.istack.internal.Nullable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by galafit on 2/6/17.
 */
public class BdfRecorderApp {
    private static final Log log = LogFactory.getLog(BdfRecorderApp.class);

    private static final int SUCCESS_STATUS = 0;
    private static final int ERROR_STATUS = 1;


    private static final String FILE_NOT_ACCESSIBLE_MSG = "File: {0}\ncould not be created or accessed.";
    private static final String COMPORT_BUSY_MSG = "ComPort: {0} is busy.";
    private static final String COMPORT_NOT_FOUND_MSG = "ComPort: {0} is not found.";
    private static final String COMPORT_NULL_MSG = "Comport name can not be null or empty";

     private static final String ALREADY_RECORDING_MSG = "Recorder is already recording. Stop it first";

    private static final String LOW_BUTTERY_MSG = "The buttery is low. BdfRecorder was stopped.";

    private static final String FAILED_CREATE_DIR_MSG = "Directory: {0}\ncan not be created.";
    private static final String DIRECTORY_EXIST_MSG = "Directory: {0}\nalready exist.";
    private static final String DIRECTORY_NOT_EXIST_MSG = "Directory: {0}\ndoes not exist.";

    private static final String FAILED_WRITE_DATA_MSG = "Failed to write data record {0} to the file:\n{1}";

    private static final String FAILED_STOP_MSG = "Failed to stop recorder.";
    private static final String FAILED_CLOSE_FILE_MSG = "File: {0} was not correctly saved";
    private static final String FAILED_DISCONNECT_MSG = "Failed to disconnect from comport {0}";

    private static final String START_FAILED_MSG = "Start failed!\nCheck whether the Recorder is on" +
            "\nand selected ComPort is correct and try again.";
    private static final String START_CANCELLED_MSG = "Start cancelled";
    private static final String WRONG_DEVICE_TYPE_MSG = "Start cancelled.\nSpecified Recorder type is invalid: {0}.\nConnected: {1}";


    private volatile Preferences preferences;
    private volatile BdfRecorder bdfRecorder;
    private volatile File edfFile;
    private volatile EdfWriter edfWriter;
    private volatile Boolean[] leadOffBitMask;

    private AtomicLong numberOfWrittenDataRecords = new AtomicLong(0);

    private int NOTIFICATION_PERIOD_MS = 1000;
    private int CONNECTION_PERIOD_MS = 2000;
    private final Timer notificationTimer = new Timer("Notification timer");
    private final ExecutorService singleThreadExecutor;

    private final MessageSender messageSender = new MessageSender();
    private volatile NotificationListener notificationListener;
    private Future connectionFuture;


    public BdfRecorderApp(Preferences preferences, String comportName) {
        this.preferences = preferences;
        notificationListener = new NullNotificationListener();

        ThreadFactory namedThreadFactory = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                return new Thread(r, "«Comport connecting» thread");
            }
        };

        singleThreadExecutor = Executors.newSingleThreadExecutor(namedThreadFactory);

        notificationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                fireNotification();
            }
        }, NOTIFICATION_PERIOD_MS, NOTIFICATION_PERIOD_MS);

        if(comportName != null && !comportName.isEmpty()) {
            connectionFuture = singleThreadExecutor.submit(new ConnectionTask(comportName));
        }
    }

    public void setComport(String comportName) {
        // if recorder already connected with that port
        if(bdfRecorder != null && bdfRecorder.getComportName().equals(comportName)) {
            return;
        }
        disconnect1();
        // restart connection task with new comport name
        if(connectionFuture != null && !connectionFuture.isDone()) {
            connectionFuture.cancel(true);
        }
        connectionFuture = singleThreadExecutor.submit(new ConnectionTask(comportName));
    }

    private void fireNotification() {
        notificationListener.update();
    }

    private void sendMessage(String msg) {
        messageSender.sendMessage(msg);
    }


    class ConnectionTask implements Runnable {
        private final String comportName;
        private boolean isRecorderCreated = false;

        public ConnectionTask(String comportName) {
            this.comportName = comportName;
        }

        @Override
        public void run() {
            while(!Thread.currentThread().isInterrupted() && !isRecorderCreated) {
                try {
                    createRecorder(comportName);
                    startMonitoring();
                    isRecorderCreated = true;
                } catch (ConnectionRuntimeException e) {
                    try {
                        Thread.sleep(CONNECTION_PERIOD_MS);
                    } catch (InterruptedException e1) {
                        break;
                    }
                }
            }
        }
    }


    private synchronized void createRecorder(String comportName) throws ConnectionRuntimeException {
       if(bdfRecorder == null) {
           bdfRecorder = new BdfRecorder(comportName);
           bdfRecorder.addEventsListener(new EventsListener() {
               @Override
               public void handleLowBattery() {
                   sendMessage(LOW_BUTTERY_MSG);
               }
           });
           bdfRecorder.addLeadOffListener(new LeadOffListener() {
               @Override
               public void onLeadOffMaskReceived(Boolean[] leadOffMask) {
                   leadOffBitMask = leadOffMask;
               }
           });
       }
    }

    private synchronized  void startMonitoring() throws IllegalStateException {
        bdfRecorder.startMonitoring();
    }

    private synchronized void startRecording1(RecorderConfig recorderConfig) throws IllegalStateException {
        bdfRecorder.startRecording(recorderConfig);
    }

    private synchronized boolean disconnect1() {
        if(bdfRecorder.disconnect()) {
            bdfRecorder = null;
            return true;
        }
        return false;
    }


    public OperationResult startRecording(AppConfig appConfig) {
        String comportName = appConfig.getComportName();

        if(isRecording()) {
            return new OperationResult(false, ALREADY_RECORDING_MSG);
        }

        if(comportName == null || comportName.isEmpty()) {
            return new OperationResult(false, COMPORT_NULL_MSG);
        }

        if(connectionFuture != null && !connectionFuture.isDone()) {
            connectionFuture.cancel(true);
        }

        if(bdfRecorder != null) {
            if(!bdfRecorder.getComportName().equals(comportName)) {
                disconnect();
            }
        }

        try {
            createRecorder(comportName);
        } catch (ConnectionRuntimeException ex) {
            String errMSg = ex.getMessage();
            if(ex.getExceptionType() == ConnectionRuntimeException.TYPE_PORT_BUSY) {
                errMSg = MessageFormat.format(COMPORT_BUSY_MSG, comportName);
            }
            if(ex.getExceptionType() == ConnectionRuntimeException.TYPE_PORT_NOT_FOUND) {
                errMSg = MessageFormat.format(COMPORT_NOT_FOUND_MSG, comportName);
            }
            log.error(ex);
            return new OperationResult(false, errMSg);
        }

        RecorderConfig recorderConfig = appConfig.getRecorderConfig();
        // remove all previously added filters
        bdfRecorder.removeChannelsFilters();

        // Apply MovingAverage filters to to ads channels to reduce 50Hz noise
        for (int i = 0; i < recorderConfig.getChannelsCount(); i++) {
            if (appConfig.is50HzFilterEnabled(i)) {
                int numberOfAveragingPoints = recorderConfig.getChannelFrequency(i) / 50;
                bdfRecorder.addChannelFilter(i, new MovingAverageFilter(numberOfAveragingPoints), "MovAvg:"+ numberOfAveragingPoints);
            }
        }

        String dirToSave = appConfig.getDirToSave();
        if(!isDirectoryExist(dirToSave)) {
            String errMSg = MessageFormat.format(DIRECTORY_NOT_EXIST_MSG, dirToSave);
            return new OperationResult(false, errMSg);
        }

        File fileToWrite = new File(dirToSave, normalizeFilename(appConfig.getFileName()));
        boolean isDurationOfDataRecordComputable = appConfig.isDurationOfDataRecordComputable();

        DataConfig dataConfig = bdfRecorder.getDataConfig(recorderConfig);
        // copy data from dataConfig to the EdfHeader
        EdfHeader edfHeader = configToHeader(dataConfig);
        edfHeader.setPatientIdentification(appConfig.getPatientIdentification());
        edfHeader.setRecordingIdentification(appConfig.getRecordingIdentification());


        try {
            edfWriter = new EdfWriter(fileToWrite, edfHeader);
        } catch (FileNotFoundException ex) {
            log.error(ex);
            String errMSg = MessageFormat.format(FILE_NOT_ACCESSIBLE_MSG, fileToWrite);
            return new OperationResult(false, errMSg);
        }
        edfWriter.setDurationOfDataRecordsComputable(isDurationOfDataRecordComputable);
        edfFile = fileToWrite;

        numberOfWrittenDataRecords.set(0);

        bdfRecorder.addDataListener(new DataListener() {
            @Override
            public void onDataReceived(int[] dataRecord) {
                try{
                    edfWriter.writeDigitalRecord(dataRecord);
                } catch (IOException ex) {
                    // although stop() will be called from not-GUI thread
                    // it could not coincide with startRecording() course
                    // DataListener works only when recorder is already "recording".
                    // And if it coincide with another stop() called from GUI or
                    // it will not course any problem
                    stop1();
                    String errMsg = MessageFormat.format(FAILED_WRITE_DATA_MSG, numberOfWrittenDataRecords.get() + 1, edfFile);
                    log.error(errMsg + "\n"+ex.getMessage());
                    sendMessage(errMsg + "\n"+ex.getMessage());
                }
                numberOfWrittenDataRecords.incrementAndGet();
            }
        });

        Future<Boolean> startFuture = bdfRecorder.startRecording(recorderConfig);
        singleThreadExecutor.submit(new StartFutureHandlerTask(startFuture, edfWriter, recorderConfig.getDeviceType()));
        return new OperationResult(true);
    }

    class StartFutureHandlerTask implements Runnable {
        private Future<Boolean> future;
        private EdfWriter edfWriter1;
        private RecorderType recorderType;

        public StartFutureHandlerTask(Future future, EdfWriter edfWriter, RecorderType recorderType) {
            this.future = future;
            this.edfWriter1 = edfWriter;
            this.recorderType = recorderType;
        }

        @Override
        public void run() {
            while (!future.isDone()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // do nothing
                }
            }

            try {
                String errMsg = START_FAILED_MSG;
                if(!future.get()) {
                    cancelStart();
                    if(recorderType != bdfRecorder.getDeviceType()) {
                        errMsg = MessageFormat.format(WRONG_DEVICE_TYPE_MSG, recorderType, bdfRecorder.getDeviceType());
                    }
                    sendMessage(errMsg);
                }
            } catch (InterruptedException e) {
                cancelStart();
            } catch (CancellationException e) {
                cancelStart();
            } catch (ExecutionException e) {
                cancelStart();
                sendMessage(e.getMessage());
            }
        }

        private void cancelStart() {
            leadOffBitMask = null;
            try {
                edfWriter1.close();
            } catch (Exception ex) {
                log.error(ex);
            }
            File writtenFile = edfWriter1.getFile();
            writtenFile.delete();
            startMonitoring();
        }
    }

    private OperationResult stop1() {
        boolean isStopSuccess = true;
        boolean isFileCloseSuccess = true;
        leadOffBitMask = null;
        String msg = "";
        if(bdfRecorder != null) {
            isStopSuccess = bdfRecorder.stop();
            if(!isStopSuccess) {
                log.error(FAILED_STOP_MSG);
                msg = FAILED_STOP_MSG;
            }
            if(edfWriter != null) {
                try {
                    edfWriter.close();
                    edfWriter = null;
                } catch (Exception ex) {
                    isFileCloseSuccess = false;
                    log.error(ex);
                    if(!msg.isEmpty()) {
                        msg = "msg"+"\n";
                    }
                    msg = msg + MessageFormat.format(FAILED_CLOSE_FILE_MSG, edfFile)+"\n" + ex.getMessage();
                }
            }
        }
        return new OperationResult(isStopSuccess && isFileCloseSuccess, msg);
    }


    public OperationResult stop() {
        OperationResult stopResult = stop1();
        if(bdfRecorder != null) {
            bdfRecorder.startMonitoring();
        }
        return stopResult;
    }


    private OperationResult disconnect()  {
        if(bdfRecorder != null) {
            OperationResult stopResult = stop1();
            String errMsg = stopResult.getMessage();

            boolean isDisconnectedOk = disconnect1();
            if(!isDisconnectedOk) {
               String disconnectionErrMsg = MessageFormat.format(FAILED_DISCONNECT_MSG, bdfRecorder.getComportName());
               log.error(disconnectionErrMsg);
               if(errMsg.isEmpty()) {
                   errMsg = disconnectionErrMsg;
               } else {
                   errMsg = errMsg + "\n"+disconnectionErrMsg;
               }
            }
            edfFile = null;
            bdfRecorder = null;
            leadOffBitMask = null;
            return new OperationResult(stopResult.isSuccess && isDisconnectedOk, errMsg);
        }
        return new OperationResult(true);

    }

    public Boolean[] getLeadOffMask() {
        return leadOffBitMask;
    }

    public boolean isDirectoryExist(String directory) {
        File dir = new File(directory);
        if(dir.exists() && dir.isDirectory()) {
            return true;
        }
        return false;
    }

    /**
     * Create directory if it does not exist.
     * @param directory name of the directory to create
     * @return OperationResult: successful if  and only if the  directory was created
     */
    public OperationResult createDirectory(String directory)  {
        File dir = new File(directory);
        if(isDirectoryExist(directory)) {
            String errMSg = MessageFormat.format(DIRECTORY_EXIST_MSG, dir.getName());
            return new OperationResult(false, errMSg);
        }
        try {
            if(dir.mkdir()) {
                return new OperationResult(true);
            } else {
                String errMSg = MessageFormat.format(FAILED_CREATE_DIR_MSG, dir);
                return new OperationResult(false, errMSg);
            }

        } catch (Exception ex) {
            String errMSg = MessageFormat.format(FAILED_CREATE_DIR_MSG, dir) + "\n"+ex.getMessage();
            return new OperationResult(false, errMSg);
        }
    }


    /**
     * If the comportName is not equal to any available port we add it to the list.
     * <p>
     * String full comparison is very "heavy" operation.
     * So instead we will compare only lengths and 2 last symbols...
     * That will be quick and good enough for our purpose
     * @return available comports list with selected port included
     */
    public String[] getComportNames(String selectedComportName) {
        String[] availablePorts = BdfRecorder.getAvailableComportNames();
        if(selectedComportName == null || selectedComportName.isEmpty()) {
            return availablePorts;
        }
        if(availablePorts.length == 0) {
            String[] resultantPorts = new String[1];
            resultantPorts[0] = selectedComportName;
            return resultantPorts;
        }

        boolean isSelectedPortAvailable = false;
        for (String port : availablePorts) {
            if(port.length() == selectedComportName.length()
                    && port.charAt(port.length() - 1) == selectedComportName.charAt(selectedComportName.length() - 1)
                    && port.charAt(port.length() - 2) == selectedComportName.charAt(selectedComportName.length() - 2)) {
                isSelectedPortAvailable = true;
                break;
            }
        }
        if(isSelectedPortAvailable) {
            return availablePorts;
        } else {
            String[] resultantPorts = new String[availablePorts.length + 1];
            resultantPorts[0] = selectedComportName;
            System.arraycopy(availablePorts, 0, resultantPorts, 1, availablePorts.length);
            return resultantPorts;
        }
    }

    public void setNotificationListener(NotificationListener l) {
        notificationListener = l;
    }

    public void setMessageListener(MessageListener l) {
        messageSender.addMessageListener(l);
    }

    public boolean isRecording() {
        if(bdfRecorder != null &&  bdfRecorder.isRecording()) {
            return true;
        }
        return false;
    }

    public boolean isActive() {
        if(bdfRecorder != null && bdfRecorder.isActive()) {
            return true;
        }
        return false;
    }

    public String getStateReport() {
        String stateString = "Disconnected";
        if(isActive()) {
            stateString = "Connected";
        }

        if(isRecording()) {
            if(numberOfWrittenDataRecords.get() == 0) {
                stateString = "Starting...";
            } else {
                stateString = "Recording... " + numberOfWrittenDataRecords + " data records";
            }
        } else {
            if(numberOfWrittenDataRecords.get() > 0) {
                stateString = "Saved to file: " + edfFile;
            }
        }
        return stateString;
    }

    public static String normalizeFilename(@Nullable String filename) {
        String FILE_EXTENSION = "bdf";
        String defaultFilename = new SimpleDateFormat("dd-MM-yyyy_HH-mm").format(new Date(System.currentTimeMillis()));

        if (filename == null || filename.isEmpty()) {
            return defaultFilename.concat(".").concat(FILE_EXTENSION);
        }
        filename = filename.trim();

        // if filename has no extension
        if (filename.lastIndexOf('.') == -1) {
            filename = filename.concat(".").concat(FILE_EXTENSION);
            return defaultFilename + filename;
        }
        // if  extension  match with given FILE_EXTENSIONS
        // (?i) makes it case insensitive (catch BDF as well as bdf)
        if (filename.matches("(?i).*\\." + FILE_EXTENSION)) {
            return defaultFilename +filename;
        }
        // If the extension do not match with  FILE_EXTENSION We need to replace it
        filename = filename.substring(0, filename.lastIndexOf(".") + 1).concat(FILE_EXTENSION);
        return defaultFilename + "_" + filename;
    }


    private void closeApplication(int status) {
        notificationTimer.cancel();
        singleThreadExecutor.shutdownNow();
        messageSender.stop();
        if(bdfRecorder != null) {
            bdfRecorder.disconnect();
        }
        System.exit(status);
    }

    public void closeApplication(AppConfig appConfig) {
        try{
            preferences.saveConfig(appConfig);
        } catch (Exception ex) {
            String errMsg = "Error during saving preferences";
            log.error(errMsg, ex);
        }
        closeApplication(SUCCESS_STATUS);
    }


    /*
     * copy data from dataConfig to the EdfHeader
     */
    private EdfHeader configToHeader(DataConfig dataConfig) {
        EdfHeader edfHeader = new EdfHeader(DataFormat.BDF_24BIT, dataConfig.signalsCount());
        edfHeader.setDurationOfDataRecord(dataConfig.getDurationOfDataRecord());
        for (int i = 0; i < dataConfig.signalsCount(); i++) {
            edfHeader.setNumberOfSamplesInEachDataRecord(i, dataConfig.getNumberOfSamplesInEachDataRecord(i));
            edfHeader.setPrefiltering(i, dataConfig.getPrefiltering(i));
            edfHeader.setTransducer(i, dataConfig.getTransducer(i));
            edfHeader.setLabel(i, dataConfig.getLabel(i));
            edfHeader.setDigitalRange(i, dataConfig.getDigitalMin(i), dataConfig.getDigitalMax(i));
            edfHeader.setPhysicalRange(i, dataConfig.getPhysicalMin(i), dataConfig.getPhysicalMax(i));
            edfHeader.setPhysicalDimension(i, dataConfig.getPhysicalDimension(i));
        }
        return edfHeader;
    }


    class NullNotificationListener implements NotificationListener {
        @Override
        public void update() {
            // do nothing
        }
    }

}
