package com.biorecorder.ads;


import com.biorecorder.dataformat.DataConfig;
import com.biorecorder.dataformat.DefaultDataConfig;
import com.sun.istack.internal.Nullable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ads packs samples from all channels received during the
 * time = MaxDiv/getSampleRate (getDurationOfDataRecord)
 * in one array of int. Every array (data record or data package) has
 * the following structure (in case of 8 channels):
 * <p>
 * <br>{
 * <br>  n_0 samples from ads_channel_0 (if this ads channel enabled)
 * <br>  n_1 samples from ads_channel_1 (if this ads channel enabled)
 * <br>  ...
 * <br>  n_8 samples from ads_channel_8 (if this ads channel enabled)
 * <br>  n_acc_x samples from accelerometer_x channel
 * <br>  n_acc_y samples from accelerometer_y channel
 * <br>  n_acc_z samples from accelerometer_Z channel
 * <br>  1 sample with BatteryVoltage info (if BatteryVoltageMeasure  enabled)
 * <br>  1 sample with lead-off detection info (if lead-off detection enabled)
 * <br>}
 * <p>
 * Where n_i = ads_channel_i_sampleRate * getDurationOfDataRecord
 * <br>ads_channel_i_sampleRate = sampleRate / ads_channel_i_divider
 * <p>
 * n_acc_x = n_acc_y = n_acc_z =  accelerometer_sampleRate * getDurationOfDataRecord
 * <br>accelerometer_sampleRate = sampleRate / accelerometer_divider
 * <p>
 * If for Accelerometer  one channel mode is chosen then samples from
 * acc_x_channel, acc_y_channel and acc_z_channel will be summarized and data records will
 * have "one accelerometer channel" instead of three:
 * <p>
 * <br>{
 * <br>  n_0 samples from ads_channel_0 (if this ads channel enabled)
 * <br>  n_1 samples from ads_channel_1 (if this ads channel enabled)
 * <br>  ...
 * <br>  n_8 samples from ads_channel_8 (if this ads channel enabled)
 * <br>  n_acc samples from accelerometer channels
 * <br>  1 sample with BatteryVoltage info (if BatteryVoltageMeasure  enabled)
 * <br>  1 (for 2 channels) or 2 (for 8 channels) samples with lead-off detection info (if lead-off detection enabled)
 * <br>}
 * <p>
 * Where n_acc =  accelerometer_sampleRate * getDurationOfDataRecord
 */
public class Ads {
    private static final Log log = LogFactory.getLog(Ads.class);

    private final int COMPORT_SPEED = 460800;
    private final byte PING_COMMAND = (byte) (0xFB & 0xFF);
    private final byte HELLO_REQUEST = (byte) (0xFD & 0xFF);
    private final byte STOP_REQUEST = (byte) (0xFF & 0xFF);
    private final byte HARDWARE_REQUEST = (byte) (0xFA & 0xFF);

    private static final int PING_PERIOD_MS = 1000;
    private static final int MONITORING_PERIOD_MS = 1000;
    private static final int ACTIVE_PERIOD_MS = 2000;
    private static final int SLEEP_TIME_MS = 1000;

    private static final int MAX_STARTING_TIME_MS = 30 * 1000;

    private static final String DISCONNECTED_MSG = "Ads is disconnected and its work is finalised";
    private static final String RECORDING_MSG = "Ads is recording. Stop it first";
    private static final String ALL_CHANNELS_DISABLED_MSG = "All Ads channels are disabled. Recording Impossible";


    private final Comport comport;

    private volatile long lastEventTime;
    private volatile boolean isDataReceived;

    private volatile AdsType adsType;

    // we use AtomicReference to do atomic "compare and set"
    // from different threads: the main thread
    // and frameDecoder thread (handling Ads messages)
    private AtomicReference<AdsState> adsStateAtomicReference =
            new AtomicReference<AdsState>(AdsState.UNDEFINED);


    private ExecutorService singleThreadExecutor;
    private volatile Future executorFuture;

    private volatile NumberedDataListener dataListener;
    private volatile MessageListener messageListener;

    public Ads(String comportName) throws ComportRuntimeException {
        comport = new Comport(comportName, COMPORT_SPEED);
        dataListener = new NullDataListener();
        messageListener = new NullMessageListener();
        ThreadFactory namedThreadFactory = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                return new Thread(r, "«Executor» thread");
            }
        };
        singleThreadExecutor = Executors.newSingleThreadExecutor(namedThreadFactory);
    }

    /**
     * Start "monitoring timer" which every second sends to
     * Ads some request (HARDWARE_REQUEST or HELLO request ) to check that
     * Ads is connected and ok. Can be called only if Ads is NOT recording
     *
     * @throws IllegalStateException if Ads was disconnected and its work is finalised
     *                               or if it is recording and should be stopped first
     */
    public void startMonitoring() throws IllegalStateException {
        if (!comport.isOpened()) {
            throw new IllegalStateException(DISCONNECTED_MSG);
        }

        if (adsStateAtomicReference.get() == AdsState.RECORDING) {
            throw new IllegalStateException(RECORDING_MSG);
        }
        // create frame decoder to handle ads messages
        comport.addListener(createAndConfigureFrameDecoder(null));

        if (adsStateAtomicReference.get() == AdsState.UNDEFINED) {
            comport.writeByte(STOP_REQUEST);
        }
        if (executorFuture == null || executorFuture.isDone()) {
            executorFuture = singleThreadExecutor.submit(new MonitoringTask());
        }
    }

    /**
     * Start Ads measurements. Stop monitoring if it was activated before
     *
     * @param config object with ads config info
     * @return Future<Boolean> that get true if starting  was successful
     * and false otherwise. Usually starting fails due to device is not connected
     * or wrong device type is specified in config (that does not coincide
     * with the really connected device type)
     * @throws IllegalStateException if Ads was disconnected and its work was finalised
     *                               or if it is already recording and should be stopped first
     * @throws IllegalArgumentException if all Ads channels are disabled
     *
     */

    public Future<Boolean> startRecording(AdsConfig config) throws IllegalStateException, IllegalArgumentException {
        if (!comport.isOpened()) {
            throw new IllegalStateException(DISCONNECTED_MSG);
        }

        if (adsStateAtomicReference.get() == AdsState.RECORDING) {
            throw new IllegalStateException(RECORDING_MSG);
        }
        // copy config because we will change it
        AdsConfig adsConfig = new AdsConfig(config);
        boolean isAllChannelsDisabled = true;
        for (int i = 0; i < adsConfig.getAdsChannelsCount(); i++) {
            if(adsConfig.isAdsChannelEnabled(i)) {
                isAllChannelsDisabled = false;
            } else {
                adsConfig.setAdsChannelCommutatorState(i, Commutator.INPUT_SHORT);
                adsConfig.setAdsChannelRldSenseEnabled(i, false);
            }
        }
        if(isAllChannelsDisabled) {
            throw new IllegalArgumentException(ALL_CHANNELS_DISABLED_MSG);
        }


        // stop monitoring
        if (executorFuture != null && !executorFuture.isDone()) {
            executorFuture.cancel(true);
        }

        isDataReceived = false;
        // create frame decoder corresponding to the configuration
        // and set it as listener to comport
        comport.addListener(createAndConfigureFrameDecoder(adsConfig));
        AdsState stateBeforeStart = adsStateAtomicReference.get();
        adsStateAtomicReference.set(AdsState.RECORDING);
        executorFuture = singleThreadExecutor.submit(new StartingTask(adsConfig, stateBeforeStart));
        return executorFuture;
    }


    class StartingTask implements Callable<Boolean> {
        private AdsConfig config;
        private AdsState stateBeforeStart;

        public StartingTask(AdsConfig config, AdsState stateBeforeStart) {
            this.config = config;
            this.stateBeforeStart = stateBeforeStart;
        }

        @Override
        public Boolean call() throws Exception {
            try {
                boolean isStartOk = start();
                if (isStartOk) {
                    // 4) start ping timer
                    // ping timer permits Ads to detect bluetooth connection problems
                    // and restart connection when it is necessary
                    executorFuture = singleThreadExecutor.submit(new PingTask(), PING_PERIOD_MS);
                } else {
                    cancel();
                }
                return isStartOk;
            } catch (Exception ex) {
                cancel();
                throw ex;
            }
        }

        private boolean start() {
            long startTime = System.currentTimeMillis();
            // 1) to correctly start we need to be sure that the specified in config adsType is ok
            while (!Thread.currentThread().isInterrupted() && adsType == null && (System.currentTimeMillis() - startTime) < MAX_STARTING_TIME_MS) {
                comport.writeByte(HARDWARE_REQUEST);
                try {
                    Thread.sleep(SLEEP_TIME_MS);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            // if adsType is ok
            if (!Thread.currentThread().isInterrupted() && adsType != null && adsType == config.getAdsType()) {
                // 2) try to stop ads first if it was not stopped before
                if (stateBeforeStart == AdsState.UNDEFINED) {
                    if (comport.writeByte(STOP_REQUEST)) {
                        // give the ads time to stop
                        try {
                            Thread.sleep(SLEEP_TIME_MS);
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }

                if (!Thread.currentThread().isInterrupted()) {
                    // 3) send "start" command
                    boolean startOk = comport.writeBytes(config.getAdsConfigurationCommand());
                    if (startOk) {
                        // 4) waiting for data

                        while (!Thread.currentThread().isInterrupted() && !isDataReceived && (System.currentTimeMillis() - startTime) < MAX_STARTING_TIME_MS) {
                            try {
                                Thread.sleep(SLEEP_TIME_MS);
                            } catch (InterruptedException e) {
                                return false;
                            }
                        }
                        if (isDataReceived) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void cancel() {
            try {
                comport.writeByte(STOP_REQUEST);
            } catch (Exception ex) {
                // do nothing;
            }
            adsStateAtomicReference.set(AdsState.UNDEFINED);
        }
    }

    private boolean stop1() {
        // cancel starting, pinging or monitoring
        if (executorFuture != null && !executorFuture.isDone()) {
            executorFuture.cancel(true);
        }

        if (adsStateAtomicReference.get() == AdsState.RECORDING) {
            adsStateAtomicReference.set(AdsState.UNDEFINED);
        }
        // send stop command
        boolean isStopOk = comport.writeByte(STOP_REQUEST);
        if (isStopOk) {
            // give ads time to stop
            try {
                Thread.sleep(SLEEP_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return isStopOk;
    }

    /**
     * Stops ads measurements or monitoring
     *
     * @throws IllegalStateException if Ads was disconnected and its work is finalised
     */
    public boolean stop() throws IllegalStateException {
        if (!comport.isOpened()) {
            throw new IllegalStateException(DISCONNECTED_MSG);
        }
        // create frame decoder to handle ads messages
        comport.addListener(createAndConfigureFrameDecoder(null));

        return stop1();
    }

    public boolean disconnect() {
        if (!comport.isOpened()) {
            return true;
        }
        if(adsStateAtomicReference.get() == AdsState.RECORDING) {
           stop1();
        }
        if (comport.close()) {
            singleThreadExecutor.shutdownNow();
            removeDataListener();
            removeMessageListener();
            return true;
        }
        return false;
    }

    FrameDecoder createAndConfigureFrameDecoder(@Nullable AdsConfig adsConfig) {
        FrameDecoder frameDecoder = new FrameDecoder(adsConfig);
        if (adsConfig != null) {
            frameDecoder.addDataListener(new NumberedDataListener() {
                @Override
                public void onDataReceived(int[] dataRecord, int dataRecordNumber) {
                    lastEventTime = System.currentTimeMillis();
                    isDataReceived = true;
                    notifyDataListeners(dataRecord, dataRecordNumber);
                }
            });
        }

        frameDecoder.addMessageListener(new MessageListener() {
            @Override
            public void onMessage(AdsMessageType messageType, String message) {
                if (messageType == AdsMessageType.ADS_2_CHANNELS) {
                    adsType = AdsType.ADS_2;
                    lastEventTime = System.currentTimeMillis();
                }
                if (messageType == AdsMessageType.ADS_8_CHANNELS) {
                    adsType = AdsType.ADS_8;
                    lastEventTime = System.currentTimeMillis();
                }
                if (messageType == AdsMessageType.STOP_RECORDING) {
                    adsStateAtomicReference.compareAndSet(AdsState.UNDEFINED, AdsState.STOPPED);
                }
                if (messageType == AdsMessageType.FRAME_BROKEN) {
                    log.info(message);
                }
                notifyMessageListeners(messageType, message);
            }
        });
        return frameDecoder;
    }

    /**
     * This method return true if last ads monitoring message (device_type)
     * or data_frame was received less then ACTIVE_PERIOD_MS (1 sec) ago
     */
    public boolean isActive() {
        if ((System.currentTimeMillis() - lastEventTime) <= ACTIVE_PERIOD_MS) {
            return true;
        }
        return false;
    }

    public boolean isRecording() {
        if (adsStateAtomicReference.get() == AdsState.RECORDING) {
            return true;
        }
        return false;
    }

    public AdsType getAdsType() {
        return adsType;
    }

    /**
     * Ads permits to add only ONE DataListener! So if a new listener added
     * the old one are automatically removed
     */
    public void addDataListener(NumberedDataListener listener) {
        if (listener != null) {
            dataListener = listener;
        }
    }

    /**
     * Ads permits to add only ONE MessageListener! So if a new listener added
     * the old one are automatically removed
     */
    public void addMessageListener(MessageListener listener) {
        if (listener != null) {
            messageListener = listener;
        }
    }

    public void removeDataListener() {
        dataListener = new NullDataListener();
    }

    public void removeMessageListener() {
        messageListener = new NullMessageListener();
    }

    private void notifyDataListeners(int[] dataRecord, int recordNumber) {
        dataListener.onDataReceived(dataRecord, recordNumber);

    }

    private void notifyMessageListeners(AdsMessageType adsMessageType, String additionalInfo) {
        messageListener.onMessage(adsMessageType, additionalInfo);
    }

    public String getComportName() {
        return comport.getComportName();
    }

    public static String[] getAvailableComportNames() {
        return Comport.getAvailableComportNames();
    }

    public DataConfig getDataConfig(AdsConfig adsConfig) {
        DefaultDataConfig edfConfig = new DefaultDataConfig(0);
        edfConfig.setDurationOfDataRecord(adsConfig.getDurationOfDataRecord());
        for (int i = 0; i < adsConfig.getAdsChannelsCount(); i++) {
            if (adsConfig.isAdsChannelEnabled(i)) {
                edfConfig.addSignal();
                int signalNumber = edfConfig.signalsCount() - 1;
                int channelSampleRate = adsConfig.getSampleRate().getValue() / adsConfig.getAdsChannelDivider(i);
                edfConfig.setTransducer(signalNumber, "Unknown");
                edfConfig.setPhysicalDimension(signalNumber, getAdsChannelsPhysicalDimension());
                edfConfig.setPhysicalRange(signalNumber, getAdsChannelPhysicalMin(adsConfig.getAdsChannelGain(i)), getAdsChannelPhysicalMax(adsConfig.getAdsChannelGain(i)));
                edfConfig.setDigitalRange(signalNumber, getAdsChannelsDigitalMin(adsConfig.getNoiseDivider()), getAdsChannelsDigitalMax(adsConfig.getNoiseDivider()));
                int nrOfSamplesInEachDataRecord = (int) Math.round(adsConfig.getDurationOfDataRecord() * channelSampleRate);
                edfConfig.setNumberOfSamplesInEachDataRecord(signalNumber, nrOfSamplesInEachDataRecord);
                edfConfig.setLabel(signalNumber, adsConfig.getAdsChannelName(i));
            }
        }

        if (adsConfig.isAccelerometerEnabled()) {
            int accSampleRate = adsConfig.getSampleRate().getValue() / adsConfig.getAccelerometerDivider();

            if (adsConfig.isAccelerometerOneChannelMode()) { // 1 accelerometer channels
                edfConfig.addSignal();
                int signalNumber = edfConfig.signalsCount() - 1;
                edfConfig.setLabel(signalNumber, "Accelerometer");
                edfConfig.setTransducer(signalNumber, "None");
                edfConfig.setPhysicalDimension(signalNumber, getAccelerometerPhysicalDimension(adsConfig.isAccelerometerOneChannelMode()));
                edfConfig.setPhysicalRange(signalNumber, getAccelerometerPhysicalMin(), getAccelerometerPhysicalMax());
                edfConfig.setDigitalRange(signalNumber, getAccelerometerDigitalMin(adsConfig.isAccelerometerOneChannelMode()), getAccelerometerDigitalMax(adsConfig.isAccelerometerOneChannelMode()));
                int nrOfSamplesInEachDataRecord = (int) Math.round(adsConfig.getDurationOfDataRecord() * accSampleRate);
                edfConfig.setNumberOfSamplesInEachDataRecord(signalNumber, nrOfSamplesInEachDataRecord);
            } else {
                String[] accelerometerChannelNames = {"Accelerometer X", "Accelerometer Y", "Accelerometer Z"};
                for (int i = 0; i < 3; i++) {     // 3 accelerometer channels
                    edfConfig.addSignal();
                    int signalNumber = edfConfig.signalsCount() - 1;
                    edfConfig.setLabel(signalNumber, accelerometerChannelNames[i]);
                    edfConfig.setTransducer(signalNumber, "None");
                    edfConfig.setPhysicalDimension(signalNumber, getAccelerometerPhysicalDimension(adsConfig.isAccelerometerOneChannelMode()));
                    edfConfig.setPhysicalRange(signalNumber, getAccelerometerPhysicalMin(), getAccelerometerPhysicalMax());
                    edfConfig.setDigitalRange(signalNumber, getAccelerometerDigitalMin(adsConfig.isAccelerometerOneChannelMode()), getAccelerometerDigitalMax(adsConfig.isAccelerometerOneChannelMode()));
                    int nrOfSamplesInEachDataRecord = (int) Math.round(adsConfig.getDurationOfDataRecord() * accSampleRate);
                    edfConfig.setNumberOfSamplesInEachDataRecord(signalNumber, nrOfSamplesInEachDataRecord);
                }
            }
        }
        if (adsConfig.isBatteryVoltageMeasureEnabled()) {
            edfConfig.addSignal();
            int signalNumber = edfConfig.signalsCount() - 1;
            edfConfig.setLabel(signalNumber, "Battery voltage");
            edfConfig.setTransducer(signalNumber, "None");
            edfConfig.setPhysicalDimension(signalNumber, getBatteryVoltageDimension());
            edfConfig.setPhysicalRange(signalNumber, getBatteryVoltagePhysicalMin(), getBatteryVoltagePhysicalMax());
            edfConfig.setDigitalRange(signalNumber, getBatteryVoltageDigitalMin(), getBatteryVoltageDigitalMax());
            int nrOfSamplesInEachDataRecord = 1;
            edfConfig.setNumberOfSamplesInEachDataRecord(signalNumber, nrOfSamplesInEachDataRecord);
        }
        if (adsConfig.isLeadOffEnabled()) {
            edfConfig.addSignal();
            int signalNumber = edfConfig.signalsCount() - 1;
            edfConfig.setLabel(signalNumber, "Lead Off Status");
            edfConfig.setTransducer(signalNumber, "None");
            edfConfig.setPhysicalDimension(signalNumber, getLeadOffStatusDimension());
            edfConfig.setPhysicalRange(signalNumber, getLeadOffStatusPhysicalMin(), getLeadOffStatusPhysicalMax());
            edfConfig.setDigitalRange(signalNumber, getLeadOffStatusDigitalMin(), getLeadOffStatusDigitalMax());
            int nrOfSamplesInEachDataRecord = 1;
            edfConfig.setNumberOfSamplesInEachDataRecord(signalNumber, nrOfSamplesInEachDataRecord);
        }
        return edfConfig;
    }


    /**
     * Helper method to convert digital value (integer) with lead-off info (last integer of data frame) to the bit-mask.
     * <p>
     * "Lead-Off" detection serves to alert/notify when an electrode is making poor electrical
     * contact or disconnecting. Therefore in Lead-Off detection mask TRUE means DISCONNECTED and
     * FALSE means CONNECTED (or if the channel is disabled or its lead-off detection disabled or
     * its commutator state != "input").
     * <p>
     * Every ads-channel has 2 electrodes (Positive and Negative) so in leadOff detection mask:
     * <br>
     * element-0 and element-1 correspond to Positive and Negative electrodes of ads channel 0,
     * element-2 and element-3 correspond to Positive and Negative electrodes of ads channel 1,
     * ...
     * element-14 and element-15 correspond to Positive and Negative electrodes of ads channel 8.
     * <p>
     *
     * @param leadOffInt       - integer with lead-off info
     * @param adsChannelsCount - number of ads channels (2 or 8)
     * @return leadOff detection mask or null if ads is stopped or
     * leadOff detection is disabled
     * @throws IllegalArgumentException if specified number of ads channels != 2 or 8
     */
    public static boolean[] leadOffIntToBitMask(int leadOffInt, int adsChannelsCount) throws IllegalArgumentException {
        int maskLength = 2 * adsChannelsCount; // 2 electrodes for every channel
        if (adsChannelsCount == 2) {

            boolean[] bm = new boolean[maskLength];
            for (int k = 0; k < bm.length; k++) {
                bm[k] = false;
                if (((leadOffInt >> k) & 1) == 1) {
                    bm[k] = true;
                }
            }
            return bm;
        }

        if (adsChannelsCount == 8) {
        /*
         * ads_8channel send lead-off status in different manner:
         * first byte - states of all negative electrodes from 8 channels
         * second byte - states of all positive electrodes from 8 channels
         */
            boolean[] bm = new boolean[maskLength];
            for (int k = 0; k < bm.length; k++) {
                bm[k] = false;
                if (k < 8) { // first byte
                    if (((leadOffInt >> k) & 1) == 1) {
                        bm[2 * k + 1] = true;
                    }
                } else { // second byte
                    if (((leadOffInt >> k) & 1) == 1) {
                        bm[2 * (k - 8)] = true;
                    }
                }

            }
            return bm;
        }

        String msg = "Invalid Ads channels count: " + adsChannelsCount + ". Number of Ads channels should be 2 or 8";
        throw new IllegalArgumentException(msg);
    }

    /**
     * Helper method to convert digital value (integer) with buttery charge value
     * to buttery percentage level
     * @param batteryInt - digital (int) value of battery charge
     * @return battery level (percentage)
     * @throws IllegalArgumentException if batteryInt < 0 or batteryInt > BatteryDigitalMax (10240)
     */
    public static int batteryIntToPercentage(int batteryInt) throws IllegalArgumentException {
        int batteryMax = getBatteryVoltageDigitalMax();
        if(batteryInt < 0 || batteryInt > batteryMax) {
            String errMsg = "Invalid battery digital value: "+batteryInt + " Expected > 0 and <= "+batteryMax;
            throw new IllegalArgumentException(errMsg);
        }
        return 100 * batteryInt / batteryMax;
    }

    public static double getAdsChannelPhysicalMax(Gain channelGain) {
        return 2400000 / channelGain.getValue();
    }

    public static  double getAdsChannelPhysicalMin(Gain channelGain) {
        return - getAdsChannelPhysicalMax(channelGain);
    }

    public static int getAdsChannelsDigitalMax(int noiseDivider) {
        return Math.round(8388607 / noiseDivider);
    }

    public static int getAdsChannelsDigitalMin(int noiseDivider) {
        return Math.round(-8388608 / noiseDivider);
    }


    public String getAdsChannelsPhysicalDimension() {
        return "uV";
    }

    public static double getAccelerometerPhysicalMax() {
        return 1000;
    }

    public static double getAccelerometerPhysicalMin() {
        return - getAccelerometerPhysicalMax();
    }

    public static int getAccelerometerDigitalMax(boolean isAccelerometerOneChannelMode) {
        if(isAccelerometerOneChannelMode) {
            return 2000;
        }
        return 9610;
    }

    public static int getAccelerometerDigitalMin(boolean isAccelerometerOneChannelMode) {
        if(isAccelerometerOneChannelMode) {
            return -2000;
        }
        return 4190;
    }


    public static String getAccelerometerPhysicalDimension(boolean isAccelerometerOneChannelMode) {
        if(isAccelerometerOneChannelMode) {
            return "m/sec^3";
        }
        return "mg";
    }

    public static double getBatteryVoltagePhysicalMax() {
        return 5;
    }

    public static double getBatteryVoltagePhysicalMin() {
        return 0;
    }

    public static int getBatteryVoltageDigitalMax() {
        return 10240;
    }

    public static int getBatteryVoltageDigitalMin() {
        return 0;
    }

    public static String getBatteryVoltageDimension() {
        return "V";
    }

    public static double getLeadOffStatusPhysicalMax() {
        return 65536;
    }

    public static double getLeadOffStatusPhysicalMin() {
        return 0;
    }

    public static int getLeadOffStatusDigitalMax() {
        return 65536;
    }

    public static int getLeadOffStatusDigitalMin() {
        return 0;
    }

    public static String getLeadOffStatusDimension() {
        return "Bit mask";
    }

    class PingTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    comport.writeByte(PING_COMMAND);
                    Thread.sleep(PING_PERIOD_MS);
                } catch (Exception ex) {
                    break;
                }
            }
        }
    }

    class MonitoringTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    comport.writeByte(HARDWARE_REQUEST);
                    Thread.sleep(MONITORING_PERIOD_MS);
                } catch (Exception ex) {
                    break;
                }
            }
        }

    }

    class NullMessageListener implements MessageListener {
        @Override
        public void onMessage(AdsMessageType messageType, String message) {
            // do nothing;
        }
    }

    class NullDataListener implements NumberedDataListener {
        @Override
        public void onDataReceived(int[] dataRecord, int dataRecordNumber) {
            // do nothing
        }
    }

}

