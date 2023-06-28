import org.apache.commons.math3.stat.descriptive.rank.Median
import org.locationtech.jts.algorithm.MinimumBoundingCircle
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Geometry
import qupath.lib.roi.interfaces.ROI

import static qupath.lib.gui.scripting.QPEx.*

def path = "C:\\Users\\flori\\OneDrive - FH Technikum Wien\\LU project\\QuPathScripts\\M253\\src\\"
File CHANNEL_NAME_CLASS = new File(path + "SetChannelNames.groovy")
File SEGMENTATION_CLASS = new File(path + "Segmentation.groovy")
File MEASUREMENTS_CLASS = new File(path + "Measurements.groovy")
def CHANNELS = [
        'DAPI'     : 2,
        'SMA'      : 2,
        'CD31'     : 1.5,
        'MYH11/NG2': 2,
        'EpCAM'    : 2,
]
String[] CHANNELS_TO_SEGMENT = [
        'CD31',
        'MYH11/NG2'
]
int RESOLUTION = 0
boolean VERBOSE = true

run(CHANNEL_NAME_CLASS, CHANNELS.keySet() as String[])

def server = getCurrentServer()
def log = getLogger()

if (getAllObjects().isEmpty()) {
    log.error('[-] Main: No Annotations were drawn, create at least one Annotation')
    return
}

def annotations = getAllObjects().findAll() {
    it.isAnnotation()
}

for (def object in annotations) {

    setSelectedObject(object)

    if (!object.hasChildObjects()) {
        CHANNELS_TO_SEGMENT.toList().forEach {
            run(
                    SEGMENTATION_CLASS,
                    it,
                    CHANNELS.get(it).toString(),
                    RESOLUTION.toString(),
                    "",
                    VERBOSE.toString()
            )
            setSelectedObject(object)
        }
        VERBOSE ? log.info('[*] Measurements: Adding measurements for ' + object.getName() + ' starting...') : ""
        run(
                MEASUREMENTS_CLASS,
                CHANNELS_TO_SEGMENT
        )
        VERBOSE ? log.info('[+] Measurements: Added measurements to ' + object.getName()) : ""
    }
}

static String isChannelNoisy(Map<String, Integer> channels, String channel) {
    return channels.get(channel) >= channels.values().sum() / channels.size() ? "true" : ""
}





