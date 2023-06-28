import qupath.lib.images.servers.ImageChannel
import qupath.lib.images.servers.ImageServers
import qupath.opencv.ml.pixel.PixelClassifierTools

import static qupath.lib.gui.scripting.QPEx.runPlugin
import static qupath.lib.scripting.QP.getAllObjects
import static qupath.lib.scripting.QP.getCurrentHierarchy
import static qupath.lib.scripting.QP.getCurrentServer
import static qupath.lib.scripting.QP.getDetectionObjects
import static qupath.lib.scripting.QP.getLogger
import static qupath.lib.scripting.QP.getPathClass
import static qupath.lib.scripting.QP.getSelectedObject
import static qupath.lib.scripting.QP.removeObjects
import static qupath.lib.scripting.QP.setSelectedObject

/**
 * Created by Florian Schmidt on 16 Feb 2023.
 */
/*
def CHANNELS = [
        'DAPI'     : 2,
        'SMA'      : 2,
        'CD31'     : 2,
        'MYH11/NG2': 3.5,
        'EpCAM'    : 2,
]
String[] CHANNELS_TO_SEGMENT = [
        'CD31',
        'SMA',
        'MYH11/NG2'
]*/
class Segmentation {

    // use higher K for channels with more noise
    static String channel_to_segment
    static double k = 1
    static int resolution_level = 0
    static boolean noisyChannel = false
    static boolean verbose = false
    static boolean split = false
    static double tiledThreshold = 0.0
    static double cutoff = 1.5

    static void main(String[] args) {
        def server = getCurrentServer()
        def hierarchy = getCurrentHierarchy()
        def log = getLogger()
        def channels = server.getMetadata().getChannels()

        if (!getSelectedObject()) {
            log.error("[-] Segmentation: Nothing Selected")
            return
        } else if (args.length == 0) {
            log.error("[-] Segmentation: No arguments were given, select at least channel name")
            return
        }

        populateVariables(args)

        verbose ? log.info('[*] ' + this.name + ': Segmentation Process starting for ' + channel_to_segment + "...") : ""

        int selectedChannelNumber = channels.findIndexOf {
            it.name == channel_to_segment
        }
        def selectedChannel = server.getChannel(selectedChannelNumber)

        verbose ? log.info('[*] ' + this.name + ': Calculating intensity values and threshold for ' + channel_to_segment + "...") : ""
        if (tiledThreshold == 0)
            getIntensityOfChannel(channels, selectedChannel)

        /*if (getSelectedObject().getMeasurements().get("ROI: 0.50 µm per pixel: " + channel_to_segment + ": Std.dev.") < cutoff)
            return*/

        double threshold = tiledThreshold == 0 ? calculateThreshold(selectedChannel) : tiledThreshold
        log.info("[+] " + this.name + ": Calculated threshold = " + Math.round(threshold))

        /**
         * following was adapted from:
         * https://gist.github.com/petebankhead/27c1f8cd950583452c756f3a2ea41fc0
         */

        def channelClass = getPathClass(channel_to_segment)
        def otherClass = getPathClass('Other')

        if (resolution_level != 0) {
            server = ImageServers.pyramidalize(server, server.getDownsampleForResolution(resolution_level))
        }

        verbose ? log.info('[*] ' + this.name + ': Creating detections...') : ""
        def thresholdServer = PixelClassifierTools.createThresholdServer(server, selectedChannelNumber, threshold, otherClass, channelClass)
        if (split) {
            PixelClassifierTools.createDetectionsFromPixelClassifier(
                    hierarchy,
                    thresholdServer,
                    15,
                    0,
                    PixelClassifierTools.CreateObjectOptions.SPLIT
            )
        } else {
            PixelClassifierTools.createAnnotationsFromPixelClassifier(
                    hierarchy,
                    thresholdServer,
                    15,
                    0
            )
        }
        def toRemove = getAllObjects().findAll {
            it.getPathClass() == otherClass
        }

        if (noisyChannel) {
            getDetectionObjects().forEach {
                setSelectedObject(it)
                getIntensityOfChannel(channels, selectedChannel)
                setSelectedObject(it.getParent())
            }
            toRemove.addAll(getDetectionObjects().findAll {
                it.getMeasurements().find() {
                    it.getKey().contains('Mean')
                }.getValue() >= 100
            })
        }

        removeObjects(toRemove, false)

        verbose ? log.info('[+] ' + this.name + ': Segmentation process for ' + channel_to_segment + ' finished') : ""

    }

    static boolean populateVariables(String[] args) {
        def channels = getCurrentServer().getMetadata().getChannels()
        def log = getLogger()
        if (args.length >= 1 && channels.join(" ").contains(args[0])) {
            channel_to_segment = args[0]
        } else {
            log.error("[-] Segmentation: Selected channel not found")
            return false
        }
        if (args.length >= 2 && args[1].isNumber()) {
            k = args[1] as double
        } else {
            log.error("[-] Segmentation: k-input no number")
            return false
        }
        if (args.length >= 3 && args[2].isNumber()) {
            resolution_level = args[2] as int
        } else {
            log.error("[-] Segmentation: Resolution-Input no number")
            return false
        }
        if (args.length >= 4 && !args[3].isEmpty()) {
            noisyChannel = true
        }
        if (args.length >= 5 && !args[4].isEmpty()) {
            verbose = true
        }
        if (args.length >= 6 && !args[5].isEmpty()) {
            split = true
        }
        if (args.length >= 7 && !args[6].isEmpty()) {
            tiledThreshold = args[6] as double
        }
        return true
    }

    /**
     *
     * Runs IntensityFeaturePlugin to measure pixel intensity (mean/st.div.) of chosen channel
     *
     * @param channels : list of channels from the used ImageServer
     * @param selectedChannel : channel to select to measure pixel intensity
     */
    static void getIntensityOfChannel(List<ImageChannel> channels, ImageChannel selectedChannel) {
        // String-Builder variable to concatenate JSON arguments with channel list
        String channelBuilder = ""
        // Add channel list names and set only selected channel true
        channels.eachWithIndex { ImageChannel ch, int i ->
            channelBuilder += '"channel' + (i + 1) + '"'
            if (ch == selectedChannel) {
                channelBuilder += ':true,'
            } else {
                channelBuilder += ':false,'
            }
        }

        // run selected plugin and concatenate JSON argument with String-Builder
        runPlugin(
                'qupath.lib.algorithms.IntensityFeaturesPlugin',
                '{' +
                        '"pixelSizeMicrons":0.5,' +
                        '"region":"ROI",' +
                        '"tileSizeMicrons":25.0,' +
                        channelBuilder +
                        '"doMean":true,' +
                        '"doStdDev":true,' +
                        '"doMinMax":false,' +
                        '"doMedian":false,' +
                        '"doHaralick":false,' +
                        '"haralickMin":1,' +
                        '"haralickMax":1,' +
                        '"haralickDistance":1,' +
                        '"haralickBins":32' +
                        '}'
        )
    }

    /**
     *
     * Calculates a binomial histogram threshold based on measured pixel intensity
     *
     * @param selectedChannel : list of channels from the used ImageServer
     * @return : binomial threshold value based on mean, std.div. and K of pixel intensities of selected annotation
     */
    static double calculateThreshold(ImageChannel selectedChannel) {
        // Retrieve the measurement list of the currently selected annotation
        def currentObjectMeasurementList = getSelectedObject().getMeasurements()
        // using mean and std.div. instead of median and sigma as mean changes with the amount of noise
        double mean = 0
        double stDeviation = 0

        // Search measurement list for mean and std.div.
        currentObjectMeasurementList.keySet().forEach {
            // if current measurement belongs to the selected channel and is a mean value then
            if (it.contains('Mean') && it.contains(selectedChannel.name))
            // set mean value of the measured pixel intensity
                mean = currentObjectMeasurementList.get(it)
            // if current measurement belongs to the selected channel and is a std.div. value then
            else if (it.contains('Std.dev') && it.contains(selectedChannel.name))
            // set std.div. value of the measured pixel intensity
                stDeviation = currentObjectMeasurementList.get(it)
        }

        // calculate threshold value based on mean, std.div. and preset/constant K-value
        double threshold = mean + (k * stDeviation)
        return threshold
    }

    /**
     *
     * Delete pixel intensity measurements
     *
     * @param selectedChannel : channel to select to remove measurements
     */
    static void deleteIntensityMeasurements(ImageChannel selectedChannel) {
        // Retrieve the measurement list of the currently selected annotation
        def currentObjectMeasurementList = getSelectedObject().getMeasurements()
        def measurementListKeySet = currentObjectMeasurementList.keySet()
        // search through all measurement names
        measurementListKeySet.forEach {
            // if measurement name contains channel name (hint that intensity measurements contain channel name) then
            if (it.contains(selectedChannel.name))
            // delete this found measurement
                currentObjectMeasurementList.remove(it)
        }
    }
}

