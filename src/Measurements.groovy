import ch.qos.logback.classic.util.CopyOnInheritThreadLocal
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.index.quadtree.Quadtree
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.GeometryTools

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors

import static qupath.lib.scripting.QP.addShapeMeasurements
import static qupath.lib.scripting.QP.getCurrentImageData
import static qupath.lib.scripting.QP.getCurrentServer
import static qupath.lib.scripting.QP.getLogger
import static qupath.lib.scripting.QP.getPathClass
import static qupath.lib.scripting.QP.getSelectedObject
import static qupath.lib.scripting.QP.getSelectedObjects
import static qupath.lib.scripting.QP.getSelectedROI

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

        if (detectionsList.size() == 10) {
            log.info('[*] ' + this.name + ': Adding intersection ratio...')
            calculateIntersectionRatio(
                    detectionsList.get(1) as Collection<PathObject>,
                    detectionsList.get(0) as Collection<PathObject>
            )
            log.info('[+] ' + this.name + ': added intersection ratio')
        }
        def vessels = currentObjectChildren.findAll {
            it.getPathClass() == getPathClass("CD31")
        }
        def pericyteSma = currentObjectChildren.findAll {
            it.getPathClass() == getPathClass("NG2/MYH11") || it.getPathClass() == getPathClass("SMA")
        }
        addIntersections(vessels, pericyteSma, ["SMA", "NG2/MYH11"] as String[], getSelectedObject(), 4)

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
                        detection1.getMeasurements().put("Area Ratio of " + detection.getPathClass() + "-Intersection/" + detections1[0].getPathClass(), intersectionRatioDetection1)
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

    static void addIntersections(Collection<PathObject> originObjects, Collection<PathObject> neighbors, String[] neighborNames, PathObject object, double R) {
        Quadtree qt = new Quadtree()
        for (def neighbor in neighbors) {
            def geom = neighbor.getROI().getGeometry()
            qt.insert(geom.getEnvelopeInternal(), neighbor)
        }

        for (def originObject in originObjects) {
            def originGeom = originObject.getROI().getGeometry()
            def originGeomBuff = originGeom.buffer(R)
            List<PathObject> queryResults = (Collection<PathObject>) qt.query(originGeom.getEnvelopeInternal())
            if (queryResults) {
                def intersectionGrouped = groupNeighbors(queryResults.findAll { originGeomBuff.intersects(it.getROI().getGeometry() as Geometry) } as Collection<PathObject>, neighborNames)
                if (intersectionGrouped) {
                    def groupedNeighborUnion = intersectionGrouped.stream().map {
                        def geometrieList = it.stream().map { it.getROI().getGeometry() }.collect(Collectors.toList())
                        def geom = geometrieList[0]
                        for (int i = 1; i < geometrieList.size(); i++) {

                            geom.union(geometrieList[i])
                        }
                        return geom
                    }.collect(Collectors.toList())
                    calculateIntersections(originObject, groupedNeighborUnion[1], groupedNeighborUnion[0], object, R)
                } else {
                    queryResults.forEach {
                        if (it.getROI().getGeometry().intersects(originObject.getROI().getGeometry())) {
                            calculateIntersections(originObject, it.getROI().getGeometry(), it.getROI().getGeometry(), object, R)
                        }
                    }
                }
            }
        }
    }

    static Collection<Collection<PathObject>> groupNeighbors(Collection<PathObject> neighbors, String[] neighborNames) {
        def res = new ArrayList()
        for (def neighbor in neighborNames) {
            res.add(neighbors.findAll() {
                it.getPathClass() == getPathClass(neighbor)
            })
        }
        return res
    }

    static void calculateIntersections(PathObject vesselPO, Geometry pericyte, Geometry sma, PathObject object, double R) {
        def vessel = vesselPO.getROI().getGeometry()
        def pvGeom = vessel.buffer(R)
        pvGeom = pvGeom.difference(vessel)

        def pixelCalibration = getCurrentServer().getMetadata().getPixelHeightMicrons()

        if (pericyte && sma) {
            def pericyteSmaGeom = pericyte.intersection(sma)
            pericyte = pericyte.difference(sma)
            sma = sma.difference(pericyte)

            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA-", vessel.intersection(pericyte).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2-/SMA+", vessel.intersection(sma).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA+", vessel.intersection(pericyteSmaGeom).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA-", pvGeom.intersection(pericyte).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2-/SMA+", pvGeom.intersection(sma).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA+", pvGeom.intersection(pericyteSmaGeom).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area", pvGeom.getArea() * pixelCalibration)
        } else if (!pericyte && sma) {
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA-", 0)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2-/SMA+", vessel.intersection(sma).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA-", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2-/SMA+", pvGeom.intersection(sma).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area", pvGeom.getArea() * pixelCalibration)
        } else if (pericyte && !sma) {
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA-", vessel.intersection(pericyte).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2-/SMA+", 0)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA-", pvGeom.intersection(pericyte).getArea() * pixelCalibration)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2-/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area", pvGeom.getArea() * pixelCalibration)
        } else {
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA-", 0)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2-/SMA+", 0)
            vesselPO.getMeasurements().put("CD31+-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA-", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2-/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area Intersection: NG2+/SMA+", 0)
            vesselPO.getMeasurements().put("PV-Area", pvGeom.getArea() * pixelCalibration)
        }

        def pv = PathObjects.createDetectionObject(GeometryTools.geometryToROI(pvGeom, ImagePlane.getDefaultPlane()))
        pv.setPathClass(getPathClass("CD31-PV"))
        object.addChildObject(pv)
    }
}
