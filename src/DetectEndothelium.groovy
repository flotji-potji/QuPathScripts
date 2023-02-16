/**
 * Created by Florian Schmidt on 8 Feb 2023.
 */

import org.apache.commons.math3.stat.descriptive.rank.Median
import org.locationtech.jts.algorithm.MinimumDiameter

import qupath.lib.images.servers.ImageChannel
import qupath.lib.images.servers.ImageServers
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathTileObject

import static qupath.lib.gui.scripting.QPEx.*
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.lib.roi.RoiTools

def CHANNEL_TO_SEGMENT = 'MYH11/NG2'
double K = 1.5
int RESOLUTION_LEVEL = 0                                // 0-based resolution resolutionLevel for the image pyramid (choosing 0 may be slow)

// Create a single-resolution server at the desired resolutionLevel, if required
def server = getCurrentServer()
def hierarchy = getCurrentHierarchy()
def channels = server.getMetadata().getChannels()
int selectedChannelNumber = channels.findIndexOf { it.name == CHANNEL_TO_SEGMENT }                       // 0-based index for the selectedChannel to threshold
def selectedChannel = server.getChannel(selectedChannelNumber)
def measurementUnit = server.getMetadata().getPixelCalibration()
def pixelHeight = measurementUnit.getPixelHeight()

String channelBuilder = ""
channels.eachWithIndex { ImageChannel ch, int i ->
    channelBuilder += '"channel' + (i + 1) + '"'
    if (ch == selectedChannel) {
        channelBuilder += ':true,'
    } else {
        channelBuilder += ':false,'
    }
}

runPlugin(
        'qupath.lib.algorithms.IntensityFeaturesPlugin',
        '{' +
                '"pixelSizeMicrons":2.0,' +
                '"region":"ROI",' +
                '"tileSizeMicrons":25.0,' +
                channelBuilder +
                '"doMean":true,' +
                '"doStdDev":true,' +
                '"doMinMax":true,' +
                '"doMedian":true,' +
                '"doHaralick":false,' +
                '"haralickMin":NaN,' +
                '"haralickMax":NaN,' +
                '"haralickDistance":1,' +
                '"haralickBins":32' +
                '}'
)

def currentObjectMeasurementList = getSelectedObject().getMeasurements()
double mean = 0
double stDeviation = 0

currentObjectMeasurementList.keySet().forEach {
    if (it.contains('Mean') && it.contains(selectedChannel.name))
        mean = currentObjectMeasurementList.get(it)
    else if (it.contains('Std.dev') && it.contains(selectedChannel.name))
        stDeviation = currentObjectMeasurementList.get(it)
}

double threshold = mean + (K * stDeviation)                       // Threshold value
print threshold
def aboveClass = getPathClass(CHANNEL_TO_SEGMENT) // Class for pixels above the threshold
def belowClass = getPathClass('Other')

if (RESOLUTION_LEVEL != 0) {
    server = qupath.lib.images.servers.ImageServers.pyramidalize(server, server.getDownsampleForResolution(RESOLUTION_LEVEL))
}

// Create a thresholded image
def thresholdServer = PixelClassifierTools.createThresholdServer(server, selectedChannelNumber, threshold, belowClass, aboveClass)

// Create annotations and add to the current object hierarchy
PixelClassifierTools.createAnnotationsFromPixelClassifier(hierarchy, thresholdServer, 10, 0)
//PixelClassifierTools.createDetectionsFromPixelClassifier(hierarchy, thresholdServer, 10, 0, PixelClassifierTools.CreateObjectOptions.SPLIT)

def toRemove = getAnnotationObjects().findAll {
    it.getPathClass() == belowClass
}

removeObjects(toRemove, false)

getAnnotationObjects().forEach {
    if (it.getPathClass() == aboveClass) {
        hierarchy.getSelectionModel().setSelectedObject(it)
    }
}

def selectionObject = getSelectedObject()
def roi = selectionObject.getROI()
currentObjectMeasurementList = selectedObject.getMeasurements()

addShapeMeasurements(getCurrentImageData(),
        [selectedObject],
        "AREA", "LENGTH", "CIRCULARITY", "SOLIDITY", "MAX_DIAMETER", "MIN_DIAMETER", "NUCLEUS_CELL_RATIO")

def polygons = RoiTools.splitAreaToPolygons(RoiTools.getArea(roi), roi.getC(), roi.getZ(), roi.getT())[1]
def realPolygons = RoiTools.splitAreaToPolygons(RoiTools.getArea(roi), roi.getC(), roi.getZ(), roi.getT())[0]
int nDetections = polygons.length
def minDias = new ArrayList<Double>()

for (def polygon in polygons) {
    minDias.add((new MinimumDiameter(polygon.getGeometry()).getLength() * pixelHeight) as double)
}

double maxDiameter = 0
double minDiameter = 0
double area = 0
double solidity = 0

currentObjectMeasurementList.keySet().forEach {
    if (it.contains('Max diameter')) {
        maxDiameter = currentObjectMeasurementList.get(it)
    } else if (it.contains('Min diameter')) {
        minDiameter = currentObjectMeasurementList.get(it)
    } else if (it.contains('Area')) {
        area = currentObjectMeasurementList.get(it)
    }
}

double avMaxDiameter = maxDiameter / nDetections
double avMinDiameter = minDiameter / nDetections
double avArea = area / nDetections
def medianClass = new Median()
double realMedianMinDiameter = medianClass.evaluate(minDias as double[])

String units = measurementUnit.getPixelWidthUnit()
currentObjectMeasurementList.put('Number of detections', nDetections)
currentObjectMeasurementList.put('Average maximum diameter ' + units, avMaxDiameter)
currentObjectMeasurementList.put('Average minimum diameter ' + units, avMinDiameter)
currentObjectMeasurementList.put('Average area ' + units, avArea)
currentObjectMeasurementList.put('Real average minimum diameter ' + units, (minDias.sum() / nDetections) as double)
currentObjectMeasurementList.put('Real median minimum diameter ' + units, realMedianMinDiameter)

class Segmentation {

    double K = 1.5
    String CHANNEL_TO_SEGMENT = "CD31"
    int RESOLUTION_LEVEL = 0

    static void main(String[] args) {
        def server = getCurrentServer()
        def hierarchy = getCurrentHierarchy()
        def channels = server.getMetadata().getChannels()
        int selectedChannelNumber = channels.findIndexOf { it.name == CHANNEL_TO_SEGMENT }
        def selectedChannel = server.getChannel(selectedChannelNumber)
        def measurementUnit = server.getMetadata().getPixelCalibration()
        def pixelHeight = measurementUnit.getPixelHeight()

        getIntensityOfChannel(channels, selectedChannel)

        double threshold = calculateThreshold(selectedChannel)

        deleteIntensityMeasurements(selectedChannel)

        def channelClass = getPathClass(CHANNEL_TO_SEGMENT)
        def otherClass = getPathClass('Other')

        if (RESOLUTION_LEVEL != 0) {
            server = ImageServers.pyramidalize(server, server.getDownsampleForResolution(RESOLUTION_LEVEL))
        }

        def thresholdServer = PixelClassifierTools.createThresholdServer(server, selectedChannelNumber, threshold, otherClass, channelClass)
        PixelClassifierTools.createDetectionsFromPixelClassifier(hierarchy, thresholdServer, 10, 0, PixelClassifierTools.CreateObjectOptions.SPLIT)

        def toRemove = getDetectionObjects().findAll {
            it.getPathClass() == otherClass
        }
        removeObjects(toRemove, false)
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
                        '"pixelSizeMicrons":2.0,' +
                        '"region":"ROI",' +
                        '"tileSizeMicrons":25.0,' +
                        channelBuilder +
                        '"doMean":true,' +
                        '"doStdDev":true,' +
                        '"doMinMax":false,' +
                        '"doMedian":false,' +
                        '"doHaralick":false,' +
                        '"haralickMin":NaN,' +
                        '"haralickMax":NaN,' +
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
        double threshold = mean + (this.K * stDeviation)
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
        // search through all measurement names
        removeMeasurements(PathTileObject, currentObjectMeasurementList.findAll {
            // if measurement name contains channel name (hint that intensity measurements contain channel name) then
            it.contains(selectedChannel.name)
            // delete this found measurement
        } as String[])
    }
}
