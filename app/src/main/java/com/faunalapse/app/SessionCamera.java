package com.faunalapse.app;

import org.json.JSONObject;

import java.io.File;

/**
 * The back camera as a session uses it. Camera2Session or Camera1Session, chosen per phone
 * (AGENTS.md, hard constraints). All calls come from the session thread and wait until the camera
 * has answered. A camera is opened either for photos or for video, never both.
 */
interface SessionCamera {

    /** Opens the camera and starts the preview (invisible, or on the aim screen), so exposure can
     *  settle. */
    void open() throws Exception;

    /** Focus and exposure on the ROI square (as in SessionSettings) where the phone allows it;
     *  exposure follows at once, focus at the next focus(). In a video session also the square that
     *  goes into the clips, from the next clip (describe() then has its real and saved sides). */
    void setSquare(double sideShare, double centreX, double centreY) throws Exception;

    /** Fixed focus at {@code diopters} (1 / distance in m; 0 = far away): the lens stays there and
     *  focus() no longer autofocuses. Null: autofocus again. Does nothing where the phone cannot fix
     *  the focus (describe(): "fixed_focus_possible"). Not during a clip. */
    void setFixedFocus(Float diopters) throws Exception;

    /** Where the lens is now, in diopters, as the camera last reported it; NaN when unknown. */
    float lensDiopters();

    /** One autofocus (on the square in photo mode); the lens then stays where it is. With a fixed
     *  focus, no autofocus. Returns the result for the log. */
    String focus() throws Exception;

    /** Photo mode: one full-size JPEG photo. */
    byte[] takePhoto() throws Exception;

    /** Photo mode: exposure time (ms) and ISO (light sensitivity) of the last photo into {@code r},
     *  where the camera reports them. */
    void putExposure(JSONObject r);

    /** Video mode: gets a clip of the square ready to record into {@code file}, turned {@code turn}
     *  degrees clockwise (upright), and focuses once; returns the focus result. */
    String prepareClip(File file, int turn) throws Exception;

    /** Video mode: starts recording the prepared clip. */
    void startClip() throws Exception;

    /** Video mode: stops recording and closes the clip file; returns facts for the clip's record
     *  (frames in the file and more). Throws when the file is useless. */
    JSONObject stopClip() throws Exception;

    /** Facts for the session log: camera interface, sensor orientation, sizes, frame rate; in a
     *  video session also the square's real and saved sides. */
    JSONObject describe();

    /** Stops a running clip (so its file stays readable) and closes the camera. Never throws. */
    void close();
}
