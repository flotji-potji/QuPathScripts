# ChannelSegmentationAndQuantification - Repository
## A Help to automate Segmentation and Quantification Signals based on a Selected Channel in QuPath

### Quick Start Guide

If no further adjustments are needed for quantification then only the script `Main.groovy` 
has to be run. Just download the repository and head to QuPath:
- Go to `Automate > Shared scripts... > Set script directory...` and select the `src` folder in the zip you just downloaded.
- Head again to `Automate > Shared scripts...`, there you now find the downloaded scripts. Select `Main.groovy`.
- Now the correct file path to the used scripts have to be defined. 
  - The three variables `CHANNEL_NAME_CLASS`, `SEGMENTATION_CLASS` and `MEASUREMENTS_CLASS` have to be edited. 
  - Inside the parenthesis of `new File()` are the script paths defined.
  - Copy the file paths for each file. Be careful to use double backslashes \\\
- Select ROIS/Annotations.
- Finally the script can be run and the selected area quantified.

### Content

The following parts reflect each script in this repository. Each highlighted file presents the thought
processes behind creating the scripts, explaining used methodology, and other hints.

1. `Main` - Binds everything together
2. `SetChannelNames` - Channel naming
3. `Segmentation` - Segments channel's signal 
4. `Measurements` - Responsible for adding measurements to detections
5. `DetectEndothelium` - Deprecated

### 1. Main

The knobs you have to turn to create a custom input are the following lines of code:

````groovy
File CHANNEL_NAME_CLASS = new File("C:\\Users\\flori\\OneDrive - FH Technikum Wien\\LU project\\QuPathScripts\\M253\\src\\SetChannelNames.groovy")
File SEGMENTATION_CLASS = new File("C:\\Users\\flori\\OneDrive - FH Technikum Wien\\LU project\\QuPathScripts\\M253\\src\\Segmentation.groovy")
File MEASUREMENTS_CLASS = new File("C:\\Users\\flori\\OneDrive - FH Technikum Wien\\LU project\\QuPathScripts\\M253\\src\\Measurements.groovy")
def CHANNELS = [
        'DAPI'     : 2,
        'SMA'      : 2,
        'CD31'     : 1.5,
        'MYH11/NG2': 5,
        'EpCAM'    : 2,
]
String[] CHANNELS_TO_SEGMENT = [
        'CD31',
        'MYH11/NG2'
]
int RESOLUTION = 0
boolean VERBOSE = true
````

### 2. SetChannelNames

The idea of this script is the correct nomenclature for each channel and the integration
of the channel names into classes for classification of detections. 

### 3. Segmentation

This class uses a chanel input to segment its signal. Through the use of am intensity classifier
of the selected annotation the mean and standard deviation of the pixel intensity can be calculated.
These values are used as they correlate to noise/background in the recorded channel and therefore can
serve as a binomial histogram filter of the channel. A dynamic threshold can therefore be calculated
for each drawn annotation without the need to select a custom threshold. The threshold can be 
calculated as follows $t = \mu + s * K$.

### 4. Measurements

Through the input of all chosen segmented channels the class adds shape measurements. The built-in
shape measurements of QuPath (area, length, circularity, solidity, max_diameter and min_diameter) 
are added to each detection. Another column to the measurement list of each detection is added which
is a more accurate approximation of the diameter of a shape. This approximation is calculated as
follows: $approx = solidity * min_diameter$. To quantify intersections of detections the following 
function was first used:
````groovy
static void calculateIntersectionRatio(
        Collection<PathObject> detections1, Collection<PathObject> detection2) {

    for (def object1 in detections1) {
        def oList1 = object1.getMeasurements()
        for (def object2 in detection2) {
            def oGeom1 = object1.getROI().getGeometry()
            def oGeom2 = object2.getROI().getGeometry()
            if (oGeom1.intersects(oGeom2)) {
                Geometry intersection = oGeom1.intersection(oGeom2)
                double object1Ratio = intersection.getArea() / oGeom1.getArea()
                double object2Ratio = intersection.getArea() / oGeom2.getArea()
                oList1.put('Area Ratio of Intersection/' + object1.getPathClass(),
                        object1Ratio)
                oList1.put('Area Ratio of Intersection/' + object2.getPathClass(),
                        object2Ratio)
            }
        }
    }
}
````
Unfortunately, this approach is too computational exhausting and takes too much time. To segment and
measure four tissue sections it took over an hour to complete at the highest resolution.

Therefore, multithreading was introduced to parallelize the measurement process. This can be observed
below: 
````groovy
static void calculateIntersectionRatio(
        Collection<PathObject> detections1, Collection<PathObject> detections2) {

  def resultSet = [
          pathObject  : [] as CopyOnWriteArrayList,
          measurements: [] as CopyOnWriteArrayList
  ]

  List<Thread> threads = []
  for (def object1 in detections1) {

    threads << Thread.start {
      for (def object2 in detections2) {
        def oGeom1 = object1.getROI().getGeometry()
        def oGeom2 = object2.getROI().getGeometry()
        if (oGeom1.intersects(oGeom2)) {
          Geometry intersection = oGeom1.intersection(oGeom2)
          double object1Ratio = intersection.getArea() / oGeom1.getArea()
          resultSet.pathObject.add(object1)
          resultSet.measurements.add(object1Ratio)
        }
      }
    }
  }

  getLogger().info(resultSet.pathObject.size() + "")
  resultSet.pathObject.eachWithIndex{ def entry, int i ->
    entry = entry as PathObject
    entry.getMeasurements().put('Area Ratio of Intersection/' + detections1[0].getPathClass(),
            resultSet.measurements.get(i) as double)
  }
}
````
After introducing a multithreaded approach, segmentation and analysis now takes ca 1.5 min for
+5,000 detections.