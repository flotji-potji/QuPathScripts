import org.locationtech.jts.geom.Geometry
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClass

import java.nio.file.Path

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
            setShapeMeasurements(it as Collection<PathObject>)
            calculateApproxDiameter(it as Collection<PathObject>)
        }

        if (detectionsList.size() == 2) {
            calculateIntersectionRatio(
                    detectionsList.get(0) as Collection<PathObject>,
                    detectionsList.get(1) as Collection<PathObject>
            )
        }
    }

    static void calculateIntersectionRatio(
            Collection<PathObject> detections1, Collection<PathObject> detection2) {

        for (def object1 in detections1) {
            def oList1 = object1.getMeasurements()
            for (def object2 in detection2) {
                def oList2 = object2.getMeasurements()
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
