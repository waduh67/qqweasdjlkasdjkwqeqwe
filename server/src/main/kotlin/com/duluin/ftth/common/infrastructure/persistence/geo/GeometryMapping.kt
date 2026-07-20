package com.duluin.ftth.common.infrastructure.persistence.geo

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.Coordinate as JtsCoordinate

/**
 * Jembatan antara value object geo di domain dan geometri JTS yang dipahami
 * Hibernate Spatial. Hidup di adapter persistence supaya lapisan domain tetap
 * bersih dari JTS/PostGIS.
 *
 * JTS memakai `x = longitude`, `y = latitude` — sama dengan urutan [Coordinate].
 */
object Geometries {

    private val factory = GeometryFactory(PrecisionModel(), Coordinate.SRID)

    fun point(coordinate: Coordinate): Point =
        factory.createPoint(JtsCoordinate(coordinate.longitude, coordinate.latitude))

    fun lineString(route: RoutePath): LineString =
        factory.createLineString(route.points.map { JtsCoordinate(it.longitude, it.latitude) }.toTypedArray())
}

fun Point.toCoordinate(): Coordinate = Coordinate(longitude = x, latitude = y)

fun LineString.toRoutePath(): RoutePath = RoutePath(coordinates.map { Coordinate(longitude = it.x, latitude = it.y) })
