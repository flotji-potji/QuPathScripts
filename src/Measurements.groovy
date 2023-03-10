import ch.qos.logback.classic.util.CopyOnInheritThreadLocal
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.index.quadtree.Quadtree
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClass

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

import static qupath.lib.scripting.QP.addShapeMeasurements
import static qupath.lib.scripting.QP.getCurrentImageData
import static qupath.lib.scripting.QP.getCurrentServer
import static qupath.lib.scripting.QP.getLogger
import static qupath.lib.scripting.QP.getPathClass
import static qupath.lib.scripting.QP.getSelectedObject

/**
 * Created by Florian Schmidt on 16 Feb 2023.
 */

class Measurements {

    static def unit = getCurrentServer().getMetadata().getPixelCalibration()

    static void main(String[] args) {
        def log = getLogger()
        def currentObjectChildren = getSelectedObject().getChildObjects()

        if (args.length == 0) {
            log.error("[-] " + this.name + ": nothing selected to measure")
            return
        }

        def detectionsList = new ArrayList<Collection<?>>()
        args.toList().forEach {
            def className = it
            detectionsList.add(
                    currentObjectChildren.findAll() {
                        getPathClass(className) == it.getPathClass()
                    } as Collection<PathObject>
            )
        }

        detectionsList.forEach {
            log.info('[*] ' + this.name + ': Adding shape measurements...')
            setShapeMeasurements(it as Collection<PathObject>)
            log.info('[+] ' + this.name + ': added shape measurements')
            log.info('[*] ' + this.name + ': Adding approx diameter...')
            calculateApproxDiameter(it as Collection<PathObject>)
            log.info('[+] ' + this.name + ': added approx diameter measurements')
        }

        if (detectionsList.size() == 2) {
            log.info('[*] ' + this.name + ': Adding intersection ratio...')
            calculateIntersectionRatio(
                    detectionsList.get(1) as Collection<PathObject>,
                    detectionsList.get(0) as Collection<PathObject>
            )
            log.info('[+] ' + this.name + ': added intersection ratio')
        }
    }

    static void calculateIntersectionRatio(
            Collection<PathObject> detections1, Collection<PathObject> detections2) {

        Quadtree qt = new Quadtree()
        for (def detection in detections1)
            qt.insert(detection.getROI().getGeometry().getEnvelopeInternal(), detection.getROI().getGeometry())

        for (def detection in detections2) {
            List<Object> result = qt.query(detection.getROI().getGeometry().getEnvelopeInternal())
            detection.getMeasurements().put("Total Coverage Area Ratio of intersection", 0)
            if (result) {
                for (def r in result) {
                    if (detection.getROI().getGeometry().intersects(r as Geometry)) {
                        def detection1 = detections1.find() {
                            (it.getROI().getGeometry() == r)
                        }
                        def intersection = detection.getROI().getGeometry().intersection(detection1.getROI().getGeometry())
                        double intersectionRatioDetection1 = intersection.getArea() / detection1.getROI().getGeometry().getArea()
                        double intersectionRatioDetection2 = intersection.getArea() / detection.getROI().getGeometry().getArea()
                        detection1.getMeasurements().put("Area Ratio of Intersection/" + detections1[0].getPathClass(), intersectionRatioDetection1)
                        detection.getMeasurements().put("Total Coverage Area Ratio of intersection", detection.getMeasurements().get("Total Coverage Area Ratio of intersection") + intersectionRatioDetection2)
                    }
                }
            }
        }
    }

    static void setShapeMeasurements(Collection<PathObject> detections) {
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
    }

    static void calculateApproxDiameter(Collection<PathObject> detections) {
        detections.forEach {
            double solidity = it.getMeasurements().find() {
                it.getKey().contains('Solidity')
            }.getValue()
            double minDia = it.getMeasurements().find() {
                it.getKey().contains('Min diameter')
            }.getValue()
            it.getMeasurements().put(
                    "Diameter approximation " + unit.getPixelWidthUnit(),
                    solidity * minDia
            )
        }
    }
}
