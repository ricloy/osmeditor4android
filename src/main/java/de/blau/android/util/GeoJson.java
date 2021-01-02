package de.blau.android.util;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.mapbox.geojson.CoordinateContainer;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.Geometry;
import com.mapbox.geojson.GeometryAdapterFactory;
import com.mapbox.geojson.GeometryCollection;
import com.mapbox.geojson.MultiPolygon;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.Polygon;
import com.mapbox.geojson.gson.BoundingBoxTypeAdapter;
import com.mapbox.geojson.gson.GeoJsonAdapterFactory;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.blau.android.osm.BoundingBox;
import de.blau.android.osm.ViewBox;
import de.blau.android.util.collections.FloatPrimitiveList;

/**
 * GeoJson utilities
 * 
 * @author Simon Poole
 *
 */
public final class GeoJson {

    private static final String DEBUG_TAG = GeoJson.class.getSimpleName();

    /**
     * Private constructor to stop instantiation
     */
    private GeoJson() {
        // private
    }

    /**
     * Calculate the bounding boxes of a GeoJson Polygon or MultiPolygon features outer rings
     * 
     * @param f The GeoJson feature
     * @param fakeMultiPolygon if true it assumes that Polygons are a list of outer rings
     * @return a List of BoundingBoxes, empty if no Polygons were found
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static List<BoundingBox> getBoundingBoxes(@NonNull Feature f, boolean fakeMultiPolygon) {
        List<BoundingBox> result = new ArrayList<>();
        Geometry g = f.geometry();
        if (g instanceof Polygon) {
            if (fakeMultiPolygon) {
                for (List<Point> l : ((CoordinateContainer<List<List<Point>>>) g).coordinates()) {
                    result.add(pointsBox(null, l));
                }
            } else {
                result.add(getBounds(g));
            }
        } else if (g instanceof MultiPolygon) {
            for (List<List<Point>> polygon : ((CoordinateContainer<List<List<List<Point>>>>) g).coordinates()) {
                result.add(pointsBox(null, polygon.get(0)));
            }
        } else if (g != null) { // g will be null for features without geometry
            Log.e(DEBUG_TAG, "Unhandled " + g + " fakeMultiPolygon " + fakeMultiPolygon);
        }
        return result;
    }

    /**
     * Determine the bounding box for GeoJSON geometries
     * 
     * @param g the GeoJSON Geometry
     * @return the bounding box
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static BoundingBox getBounds(@NonNull Geometry g) {
        BoundingBox result = null;
        switch (g.type()) {
        case GeoJSONConstants.POINT:
            Point p = (Point) g;
            result = new BoundingBox(p.longitude(), p.latitude());
            break;
        case GeoJSONConstants.LINESTRING:
        case GeoJSONConstants.MULTIPOINT:
            result = pointsBox(result, ((CoordinateContainer<List<Point>>) g).coordinates());
            break;
        case GeoJSONConstants.MULTIPOLYGON:
            for (List<List<Point>> polygon : ((CoordinateContainer<List<List<List<Point>>>>) g).coordinates()) {
                for (List<Point> l : polygon) {
                    result = pointsBox(result, l);
                }
            }
            break;
        case GeoJSONConstants.GEOMETRYCOLLECTION:
            for (Geometry geometry : ((GeometryCollection) g).geometries()) {
                result = expand(result, geometry);
            }
            break;
        case GeoJSONConstants.MULTILINESTRING:
        case GeoJSONConstants.POLYGON:
            for (List<Point> l : ((CoordinateContainer<List<List<Point>>>) g).coordinates()) {
                result = pointsBox(result, l);
            }
            break;
        default:
            Log.e(DEBUG_TAG, "getBounds unknown GeoJSON geometry " + g.type());
        }
        return result;
    }

    /**
     * Create a BoundingBox for a List of Points
     * 
     * @param result an input BoundingBox to expand or null
     * @param points the list of Point
     * @return a BoundginBox or null
     */
    @Nullable
    private static BoundingBox pointsBox(@Nullable BoundingBox result, @NonNull List<Point> points) {
        for (Point q : points) {
            result = expand(result, q);
        }
        return result;
    }

    /**
     * Expand a bounding box to include Geometry g
     * 
     * @param box the bounding box
     * @param g the geometry
     * @return a bounding box
     */
    @Nullable
    private static BoundingBox expand(@Nullable BoundingBox box, @NonNull Geometry g) {
        BoundingBox newBox = getBounds(g);
        if (box == null) {
            box = newBox;
        } else if (newBox != null) {
            box.union(newBox);
        }
        return box;
    }

    /**
     * Converts a geographical way/path/track to a list of screen-coordinate points for drawing.
     * 
     * Only segments that are inside the ViewBox are included. This duplicates the logic in Map for OSM objects for
     * GeoJSON (can't really be avoided)
     * 
     * @param box the current ViewBox
     * @param w screen width
     * @param h screen height
     * @param points list to (re-)use for projected points in the format expected by
     *            {@link Canvas#drawLines(float[], Paint)}
     * @param nodes A List of the Points to be drawn
     */
    public static void pointListToLinePointsArray(@NonNull ViewBox box, int w, int h, @NonNull final FloatPrimitiveList points,
            @NonNull final List<Point> nodes) {
        points.clear(); // reset
        // loop over all nodes
        Point prevNode = null;
        Point lastDrawnNode = null;
        int lastDrawnNodeLonE7 = 0;
        int lastDrawnNodeLatE7 = 0;
        float prevX = 0f;
        float prevY = 0f;
        boolean thisIntersects = false;
        boolean nextIntersects = false;
        int nodesSize = nodes.size();
        if (nodesSize > 0) {
            Point nextNode = nodes.get(0);
            double nextNodeLat = nextNode.latitude();
            double nextNodeLon = nextNode.longitude();
            int nextNodeLatE7 = (int) (nextNode.latitude() * 1E7);
            int nextNodeLonE7 = (int) (nextNode.longitude() * 1E7);
            float x;
            float y = -Float.MAX_VALUE;
            for (int i = 0; i < nodesSize; i++) {
                Point node = nextNode;
                double nodeLon = nextNodeLon;
                double nodeLat = nextNodeLat;
                int nodeLonE7 = nextNodeLonE7;
                int nodeLatE7 = nextNodeLatE7;
                nextIntersects = true;
                if (i < nodesSize - 1) {
                    nextNode = nodes.get(i + 1);
                    nextNodeLat = nextNode.latitude();
                    nextNodeLon = nextNode.longitude();
                    nextNodeLatE7 = (int) (nextNodeLat * 1E7);
                    nextNodeLonE7 = (int) (nextNodeLon * 1E7);
                    nextIntersects = box.isIntersectionPossible(nextNodeLonE7, nextNodeLatE7, nodeLonE7, nodeLatE7);
                } else {
                    nextNode = null;
                }
                x = -Float.MAX_VALUE; // misuse this as a flag
                if (prevNode != null && (thisIntersects || nextIntersects || (!(nextNode != null && lastDrawnNode != null)
                        || box.isIntersectionPossible(nextNodeLonE7, nextNodeLatE7, lastDrawnNodeLonE7, lastDrawnNodeLatE7)))) {
                    x = GeoMath.lonToX(w, box, nodeLon);
                    y = GeoMath.latToY(h, w, box, nodeLat);
                    if (prevX == -Float.MAX_VALUE) { // last segment didn't intersect
                        prevX = GeoMath.lonToX(w, box, prevNode.longitude());
                        prevY = GeoMath.latToY(h, w, box, prevNode.latitude());
                    }
                    // Line segment needs to be drawn
                    points.add(prevX);
                    points.add(prevY);
                    points.add(x);
                    points.add(y);
                    lastDrawnNode = node;
                    lastDrawnNodeLatE7 = nodeLatE7;
                    lastDrawnNodeLonE7 = nodeLonE7;
                }
                prevNode = node;
                prevX = x;
                prevY = y;
                thisIntersects = nextIntersects;
            }
        }
    }

    /**
     * Parse geojson just containing the geometry
     * 
     * @param json the geojson
     * @return a Geometry
     */
    @NonNull
    public static Geometry geometryFromJson(@NonNull String json) {
        GsonBuilder gson = new GsonBuilder();
        gson.registerTypeAdapterFactory(GeoJsonAdapterFactory.create());
        gson.registerTypeAdapterFactory(GeometryAdapterFactory.create());
        gson.registerTypeAdapter(BoundingBox.class, new BoundingBoxTypeAdapter());
        return gson.create().fromJson(json, Geometry.class);
    }
}
