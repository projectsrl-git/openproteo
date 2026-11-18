package com.legalarchive.orchestrator.control;

import java.util.Map;

/**
 * What the control channel asks of the instance. The channel itself (claiming, identity, expiry,
 * answers) knows nothing of the engine, so it runs and is tested against any implementation;
 * {@link EngineBackend} is the one that drives the real engine.
 */
public interface ControlBackend {

    /**
     * Executes one request.
     *
     * @param user  the user to record: the OS owner of the request file
     * @param extra the details every audit line of this request carries (channel, request id,
     *              declared user when it differs)
     */
    ControlProtocol.Response execute(ControlProtocol.Request request, String user, Map<String, String> extra);

    /** Audits a request that expired before it was claimed, in its feed when it names one that exists. */
    void auditExpired(ControlProtocol.Request request, String user, Map<String, String> extra);
}
