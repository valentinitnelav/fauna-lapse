package com.faunalapse.app;

import org.json.JSONObject;

import java.io.File;

/**
 * The back camera as a session uses it. Camera2Session or Camera1Session, chosen per phone
 * (AGENTS.md, hard constraints). All calls come from the session thread and wait until the camera
 * has answered. A camera is opened either for photos or for video, never both.
 */
interface SessionCamera {

    /** Opens the camera and starts an invisible preview, so exposure can settle. */
    void open() throws Exception;

    /** Photo mode: one autofocus; the lens then stays where it is. Returns the result for the log. */
    String focus() throws Exception;

    /** Photo mode: one full-size JPEG photo. */
    byte[] takePhoto() throws Exception;

    /** Video mode: gets a clip ready to record into {@code file} and focuses once; returns the focus
     *  result. */
    String prepareClip(File file) throws Exception;

    /** Video mode: starts recording the prepared clip. */
    void startClip() throws Exception;

    /** Video mode: stops recording and closes the clip file. */
    void stopClip() throws Exception;

    /** Facts for the session log: camera interface, sizes, frame rate. */
    JSONObject describe();

    /** Stops a running clip (so its file stays readable) and closes the camera. Never throws. */
    void close();
}
