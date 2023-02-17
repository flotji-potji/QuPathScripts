/**
 * Created by Florian Schmidt on 8 Feb 2023.
 */

import org.apache.commons.math3.stat.descriptive.rank.Median
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Geometry
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.images.servers.ImageChannel
import qupath.lib.images.servers.ImageServers
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathTileObject

import static qupath.lib.gui.scripting.QPEx.*
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.lib.roi.RoiTools

def CHANNEL_TO_SEGMENT = 'MYH11/NG2'
double K = 4
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
//PixelClassifierTools.createAnnotationsFromPixelClassifier(hierarchy, thresholdServer, 10, 0)
PixelClassifierTools.createDetectionsFromPixelClassifier(hierarchy, thresholdServer, 10, 0, PixelClassifierTools.CreateObjectOptions.SPLIT)

def toRemove = getDetectionObjects().findAll {
    it.getPathClass() == belowClass
}

removeObjects(toRemove, false)

def selectionObject = getSelectedObject()
def roi = selectionObject.getROI()
currentObjectMeasurementList = selectedObject.getMeasurements()
def detections = selectedObject.getChildObjects().findAll() {
    it.getPathClass() == aboveClass
}

addShapeMeasurements(
        getCurrentImageData(),
        detections,
        ObjectMeasurements.ShapeFeatures.AREA,
        ObjectMeasurements.ShapeFeatures.LENGTH,
        ObjectMeasurements.ShapeFeatures.CIRCULARITY,
        ObjectMeasurements.ShapeFeatures.SOLIDITY,
        ObjectMeasurements.ShapeFeatures.MAX_DIAMETER,
        ObjectMeasurements.ShapeFeatures.MIN_DIAMETER,
        ObjectMeasurements.ShapeFeatures.NUCLEUS_CELL_RATIO
)

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

