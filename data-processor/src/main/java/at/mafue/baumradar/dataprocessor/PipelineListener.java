package at.mafue.baumradar.dataprocessor;

import java.util.List;

/**
 * Progress callbacks for a {@link Pipeline} run. The CLI ({@link Main}) relies on
 * the pipeline's own SLF4J logging and can pass {@link #NOOP}; the future local
 * Web-UI will supply an implementation that pushes each event to the browser via
 * Server-Sent Events.
 *
 * <p>All methods are {@code default} no-ops so implementations override only what
 * they need. Callbacks fire from the pipeline's worker threads — implementations
 * must be thread-safe.
 */
public interface PipelineListener {

    /** Fired once before processing starts, with the display names of the selected cities. */
    default void onStart(List<String> cityNames) {}

    /** Fired when a city's import begins. */
    default void onCityStart(String cityId, String name) {}

    /** Fired when a city finished successfully; {@code treeCount} is the number of trees written. */
    default void onCityDone(String cityId, String name, long treeCount) {}

    /** Fired when a city failed; {@code error} is a short human-readable message. */
    default void onCityError(String cityId, String name, String error) {}

    /** Fired once after all cities and the catalog are done. */
    default void onFinished(int processed, int failed) {}

    /** Free-form progress note (geocoder dump download, cut progress, …) for the UI banner. */
    default void onNote(String message) {}

    /** Fired when a city's geocoder cut finished; {@code bytes} is the compressed file size. */
    default void onGeoCityDone(String cityId, String name, long places, long bytes) {}

    /** Fired when a city's geocoder cut failed. */
    default void onGeoCityError(String cityId, String name, String error) {}

    /** A listener that ignores every event. */
    PipelineListener NOOP = new PipelineListener() {};
}
