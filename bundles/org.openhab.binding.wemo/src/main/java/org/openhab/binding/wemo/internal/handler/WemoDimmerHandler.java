/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.wemo.internal.handler;

import static org.openhab.binding.wemo.internal.WemoBindingConstants.*;
import static org.openhab.binding.wemo.internal.WemoUtil.*;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.wemo.internal.http.WemoHttpCall;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link WemoDimmerHandler} is responsible for handling commands, which are
 * sent to one of the channels and to update their states.
 *
 * @author Hans-Jörg Merk - Initial contribution
 */
@NonNullByDefault
public class WemoDimmerHandler extends WemoBaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(WemoDimmerHandler.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_DIMMER);

    private final Object upnpLock = new Object();
    private final Object jobLock = new Object();

    private final Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<>());

    private Map<String, Boolean> subscriptionState = new HashMap<>();

    private @Nullable ScheduledFuture<?> pollingJob;

    private int currentBrightness;
    private int currentNightModeBrightness;
    private @Nullable String currentNightModeState;
    /**
     * Set dimming stepsize to 5%
     */
    private static final int DIM_STEPSIZE = 5;

    public WemoDimmerHandler(Thing thing, UpnpIOService upnpIOService, WemoHttpCall wemoHttpCaller) {
        super(thing, upnpIOService, wemoHttpCaller);

        logger.debug("Creating a WemoDimmerHandler for thing '{}'", getThing().getUID());
    }

    @Override
    public void initialize() {
        Configuration configuration = getConfig();

        if (configuration.get(UDN) != null) {
            logger.debug("Initializing WemoDimmerHandler for UDN '{}'", configuration.get(UDN));
            UpnpIOService localService = service;
            if (localService != null) {
                localService.registerParticipant(this);
            }
            host = getHost();
            pollingJob = scheduler.scheduleWithFixedDelay(this::poll, 0, DEFAULT_REFRESH_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/config-status.error.missing-udn");
            logger.debug("Cannot initalize WemoDimmerHandler. UDN not set.");
        }
    }

    @Override
    public void dispose() {
        logger.debug("WeMoDimmerHandler disposed.");

        ScheduledFuture<?> job = this.pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
        }
        this.pollingJob = null;
        removeSubscription();
    }

    private void poll() {
        synchronized (jobLock) {
            if (pollingJob == null) {
                return;
            }
            try {
                logger.debug("Polling job");
                host = getHost();
                // Check if the Wemo device is set in the UPnP service registry
                // If not, set the thing state to ONLINE/CONFIG-PENDING and wait for the next poll
                if (!isUpnpDeviceRegistered()) {
                    logger.debug("UPnP device {} not yet registered", getUDN());
                    updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "@text/config-status.pending.device-not-registered [\"" + getUDN() + "\"]");
                    synchronized (upnpLock) {
                        subscriptionState = new HashMap<>();
                    }
                    return;
                }
                updateStatus(ThingStatus.ONLINE);
                updateWemoState();
                addSubscription();
            } catch (Exception e) {
                logger.debug("Exception during poll: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.trace("Command '{}' received for channel '{}'", command, channelUID);
        if (command instanceof RefreshType) {
            try {
                updateWemoState();
            } catch (Exception e) {
                logger.debug("Exception during poll", e);
            }
        } else {
            String action = "SetBinaryState";
            String argument = "BinaryState";
            String value = "0";
            String timeStamp = null;
            switch (channelUID.getId()) {
                case CHANNEL_BRIGHTNESS:
                    String binaryState = this.stateMap.get("BinaryState");
                    if (command instanceof OnOffType) {
                        value = command.equals(OnOffType.OFF) ? "0" : "1";
                        setBinaryState(action, argument, value);
                        if (command.equals(OnOffType.OFF)) {
                            State brightnessState = new PercentType("0");
                            updateState(CHANNEL_BRIGHTNESS, brightnessState);
                            updateState(CHANNEL_TIMERSTART, OnOffType.OFF);
                        } else {
                            State brightnessState = new PercentType(currentBrightness);
                            updateState(CHANNEL_BRIGHTNESS, brightnessState);
                        }
                    } else if (command instanceof PercentType) {
                        int newBrightness = ((PercentType) command).intValue();
                        value = String.valueOf(newBrightness);
                        currentBrightness = newBrightness;
                        argument = "brightness";
                        if ("0".equals(value)) {
                            value = "1";
                            argument = "brightness";
                            setBinaryState(action, argument, "1");
                            value = "0";
                            argument = "BinaryState";
                            setBinaryState(action, argument, "0");
                        } else if ("0".equals(binaryState)) {
                            argument = "BinaryState";
                            setBinaryState(action, argument, "1");
                        }
                        argument = "brightness";
                        setBinaryState(action, argument, value);
                    } else if (command instanceof IncreaseDecreaseType) {
                        int newBrightness;
                        switch (command.toString()) {
                            case "INCREASE":
                                newBrightness = currentBrightness + DIM_STEPSIZE;
                                if (newBrightness > 100) {
                                    newBrightness = 100;
                                }
                                value = String.valueOf(newBrightness);
                                currentBrightness = newBrightness;
                                break;
                            case "DECREASE":
                                newBrightness = currentBrightness - DIM_STEPSIZE;
                                if (newBrightness < 0) {
                                    newBrightness = 0;
                                }
                                value = String.valueOf(newBrightness);
                                currentBrightness = newBrightness;
                                break;
                        }
                        argument = "brightness";
                        if ("0".equals(value)) {
                            value = "1";
                            argument = "brightness";
                            setBinaryState(action, argument, "1");
                            value = "0";
                            argument = "BinaryState";
                            setBinaryState(action, argument, "0");
                        } else if ("0".equals(binaryState)) {
                            argument = "BinaryState";
                            setBinaryState(action, argument, "1");
                        }
                        argument = "brightness";
                        setBinaryState(action, argument, value);
                    }
                    break;
                case CHANNEL_FADERCOUNTDOWNTIME:
                    argument = "Fader";
                    if (command instanceof DecimalType) {
                        int commandValue = Integer.valueOf(String.valueOf(command));
                        commandValue = commandValue * 60;
                        String commandString = String.valueOf(commandValue);
                        value = "<BinaryState></BinaryState>" + "<Duration></Duration>" + "<EndAction></EndAction>"
                                + "<brightness></brightness>" + "<fader>" + commandString + ":-1:1:0:0</fader>"
                                + "<UDN></UDN>";
                        setBinaryState(action, argument, value);
                    }
                    break;
                case CHANNEL_FADERENABLED:
                    argument = "Fader";
                    if (command.equals(OnOffType.ON)) {
                        value = "<BinaryState></BinaryState>" + "<Duration></Duration>" + "<EndAction></EndAction>"
                                + "<brightness></brightness>" + "<fader>600:-1:1:0:0</fader>" + "<UDN></UDN>";
                    } else if (command.equals(OnOffType.OFF)) {
                        value = "<BinaryState></BinaryState>" + "<Duration></Duration>" + "<EndAction></EndAction>"
                                + "<brightness></brightness>" + "<fader>600:-1:0:0:0</fader>" + "<UDN></UDN>";
                    }
                    setBinaryState(action, argument, value);
                    break;
                case CHANNEL_TIMERSTART:
                    argument = "Fader";
                    long ts = System.currentTimeMillis() / 1000;
                    timeStamp = String.valueOf(ts);
                    logger.info("timestamp '{}' created", timeStamp);
                    String faderSeconds = null;
                    String faderEnabled = null;
                    String fader = this.stateMap.get("fader");
                    if (fader != null) {
                        String[] splitFader = fader.split(":");
                        if (splitFader[0] != null) {
                            faderSeconds = splitFader[0];
                        }
                        if (splitFader[0] != null) {
                            faderEnabled = splitFader[2];
                        }
                    }
                    if (faderSeconds != null && faderEnabled != null) {
                        if (OnOffType.ON.equals(command)) {
                            value = "<BinaryState></BinaryState>" + "<Duration></Duration>" + "<EndAction></EndAction>"
                                    + "<brightness></brightness>" + "<fader>" + faderSeconds + ":" + timeStamp + ":"
                                    + faderEnabled + ":0:0</fader>" + "<UDN></UDN>";
                            updateState(CHANNEL_STATE, OnOffType.ON);
                        } else if (OnOffType.OFF.equals(command)) {
                            value = "<BinaryState></BinaryState>" + "<Duration></Duration>" + "<EndAction></EndAction>"
                                    + "<brightness></brightness>" + "<fader>" + faderSeconds + ":-1:" + faderEnabled
                                    + ":0:0</fader>" + "<UDN></UDN>";
                        }
                    }
                    setBinaryState(action, argument, value);
                    break;
                case CHANNEL_NIGHTMODE:
                    action = "ConfigureNightMode";
                    argument = "NightModeConfiguration";
                    String nightModeBrightness = String.valueOf(currentNightModeBrightness);
                    if (OnOffType.ON.equals(command)) {
                        value = "&lt;startTime&gt;0&lt;/startTime&gt; \\n&lt;nightMode&gt;1&lt;/nightMode&gt; \\n&lt;endTime&gt;23400&lt;/endTime&gt; \\n&lt;nightModeBrightness&gt;"
                                + nightModeBrightness + "&lt;/nightModeBrightness&gt; \\n";
                    } else if (OnOffType.OFF.equals(command)) {
                        value = "&lt;startTime&gt;0&lt;/startTime&gt; \\n&lt;nightMode&gt;0&lt;/nightMode&gt; \\n&lt;endTime&gt;23400&lt;/endTime&gt; \\n&lt;nightModeBrightness&gt;"
                                + nightModeBrightness + "&lt;/nightModeBrightness&gt; \\n";
                    }
                    setBinaryState(action, argument, value);
                    break;
                case CHANNEL_NIGHTMODEBRIGHTNESS:
                    action = "ConfigureNightMode";
                    argument = "NightModeConfiguration";
                    if (command instanceof PercentType) {
                        int newBrightness = ((PercentType) command).intValue();
                        String newNightModeBrightness = String.valueOf(newBrightness);
                        value = "&lt;startTime&gt;0&lt;/startTime&gt; \\n&lt;nightMode&gt;" + currentNightModeState
                                + "&lt;/nightMode&gt; \\n&lt;endTime&gt;23400&lt;/endTime&gt; \\n&lt;nightModeBrightness&gt;"
                                + newNightModeBrightness + "&lt;/nightModeBrightness&gt; \\n";
                        currentNightModeBrightness = newBrightness;
                    } else if (command instanceof IncreaseDecreaseType) {
                        int newBrightness;
                        String newNightModeBrightness = null;
                        switch (command.toString()) {
                            case "INCREASE":
                                newBrightness = currentNightModeBrightness + DIM_STEPSIZE;
                                if (newBrightness > 100) {
                                    newBrightness = 100;
                                }
                                newNightModeBrightness = String.valueOf(newBrightness);
                                currentBrightness = newBrightness;
                                break;
                            case "DECREASE":
                                newBrightness = currentNightModeBrightness - DIM_STEPSIZE;
                                if (newBrightness < 0) {
                                    newBrightness = 0;
                                }
                                newNightModeBrightness = String.valueOf(newBrightness);
                                currentNightModeBrightness = newBrightness;
                                break;
                        }
                        value = "&lt;startTime&gt;0&lt;/startTime&gt; \\n&lt;nightMode&gt;" + currentNightModeState
                                + "&lt;/nightMode&gt; \\n&lt;endTime&gt;23400&lt;/endTime&gt; \\n&lt;nightModeBrightness&gt;"
                                + newNightModeBrightness + "&lt;/nightModeBrightness&gt; \\n";
                    }
                    setBinaryState(action, argument, value);
                    break;
            }
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service != null) {
            logger.debug("WeMo {}: Subscription to service {} {}", getUDN(), service,
                    succeeded ? "succeeded" : "failed");
            subscriptionState.put(service, succeeded);
        }
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        logger.debug("Received pair '{}':'{}' (service '{}') for thing '{}'",
                new Object[] { variable, value, service, this.getThing().getUID() });
        updateStatus(ThingStatus.ONLINE);
        if (variable != null && value != null) {
            String oldBinaryState = this.stateMap.get("BinaryState");
            this.stateMap.put(variable, value);
            switch (variable) {
                case "BinaryState":
                    if (oldBinaryState == null || !oldBinaryState.equals(value)) {
                        State state = "0".equals(value) ? OnOffType.OFF : OnOffType.ON;
                        logger.debug("State '{}' for device '{}' received", state, getThing().getUID());
                        updateState(CHANNEL_BRIGHTNESS, state);
                        if (state.equals(OnOffType.OFF)) {
                            updateState(CHANNEL_TIMERSTART, OnOffType.OFF);
                        }
                    }
                    break;
                case "brightness":
                    logger.debug("brightness '{}' for device '{}' received", value, getThing().getUID());
                    int newBrightnessValue = Integer.valueOf(value);
                    State newBrightnessState = new PercentType(newBrightnessValue);
                    String binaryState = this.stateMap.get("BinaryState");
                    if (binaryState != null) {
                        if ("1".equals(binaryState)) {
                            updateState(CHANNEL_BRIGHTNESS, newBrightnessState);
                        }
                    }
                    currentBrightness = newBrightnessValue;
                    break;
                case "fader":
                    logger.debug("fader '{}' for device '{}' received", value, getThing().getUID());
                    String[] splitFader = value.split(":");
                    if (splitFader[0] != null) {
                        int faderSeconds = Integer.valueOf(splitFader[0]);
                        State faderMinutes = new DecimalType(faderSeconds / 60);
                        logger.debug("faderTime '{} minutes' for device '{}' received", faderMinutes,
                                getThing().getUID());
                        updateState(CHANNEL_FADERCOUNTDOWNTIME, faderMinutes);
                    }
                    if (splitFader[1] != null) {
                        State isTimerRunning = splitFader[1].equals("-1") ? OnOffType.OFF : OnOffType.ON;
                        logger.debug("isTimerRunning '{}' for device '{}' received", isTimerRunning,
                                getThing().getUID());
                        updateState(CHANNEL_TIMERSTART, isTimerRunning);
                        if (isTimerRunning.equals(OnOffType.ON)) {
                            updateState(CHANNEL_STATE, OnOffType.ON);
                        }
                    }
                    if (splitFader[2] != null) {
                        State isFaderEnabled = splitFader[1].equals("0") ? OnOffType.OFF : OnOffType.ON;
                        logger.debug("isFaderEnabled '{}' for device '{}' received", isFaderEnabled,
                                getThing().getUID());
                        updateState(CHANNEL_FADERENABLED, isFaderEnabled);
                    }
                    break;
                case "nightMode":
                    State nightModeState = "0".equals(value) ? OnOffType.OFF : OnOffType.ON;
                    currentNightModeState = value;
                    logger.debug("nightModeState '{}' for device '{}' received", nightModeState, getThing().getUID());
                    updateState(CHANNEL_NIGHTMODE, nightModeState);
                    break;
                case "startTime":
                    State startTimeState = getDateTimeState(value);
                    logger.debug("startTimeState '{}' for device '{}' received", startTimeState, getThing().getUID());
                    if (startTimeState != null) {
                        updateState(CHANNEL_STARTTIME, startTimeState);
                    }
                    break;
                case "endTime":
                    State endTimeState = getDateTimeState(value);
                    logger.debug("endTimeState '{}' for device '{}' received", endTimeState, getThing().getUID());
                    if (endTimeState != null) {
                        updateState(CHANNEL_ENDTIME, endTimeState);
                    }
                    break;
                case "nightModeBrightness":
                    int nightModeBrightnessValue = Integer.valueOf(value);
                    currentNightModeBrightness = nightModeBrightnessValue;
                    State nightModeBrightnessState = new PercentType(nightModeBrightnessValue);
                    logger.debug("nightModeBrightnessState '{}' for device '{}' received", nightModeBrightnessState,
                            getThing().getUID());
                    updateState(CHANNEL_NIGHTMODEBRIGHTNESS, nightModeBrightnessState);
                    break;
            }
        }
    }

    private synchronized void addSubscription() {
        UpnpIOService localService = service;
        if (localService != null) {
            if (localService.isRegistered(this)) {
                logger.debug("Checking WeMo GENA subscription for '{}'", getThing().getUID());
                String subscription = BASICEVENT;
                if (subscriptionState.get(subscription) == null) {
                    logger.debug("Setting up GENA subscription {}: Subscribing to service {}...", getUDN(),
                            subscription);
                    localService.addSubscription(this, subscription, SUBSCRIPTION_DURATION_SECONDS);
                    subscriptionState.put(subscription, true);
                }
            } else {
                logger.debug("Setting up WeMo GENA subscription for '{}' FAILED - service.isRegistered(this) is FALSE",
                        getThing().getUID());
            }
        }
    }

    private synchronized void removeSubscription() {
        logger.debug("Removing WeMo GENA subscription for '{}'", getThing().getUID());
        UpnpIOService localService = service;
        if (localService != null) {
            if (localService.isRegistered(this)) {
                String subscription = BASICEVENT;
                if (subscriptionState.get(subscription) != null) {
                    logger.debug("WeMo {}: Unsubscribing from service {}...", getUDN(), subscription);
                    localService.removeSubscription(this, subscription);
                }
                subscriptionState = new HashMap<>();
                localService.unregisterParticipant(this);
            }
        }
    }

    /**
     * The {@link updateWemoState} polls the actual state of a WeMo device and
     * calls {@link onValueReceived} to update the statemap and channels..
     *
     */
    protected void updateWemoState() {
        String localHost = getHost();
        if (localHost.isEmpty()) {
            logger.error("Failed to get actual state for device '{}': IP address missing", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-ip");
            return;
        }
        String wemoURL = getWemoURL(localHost, BASICACTION);
        if (wemoURL == null) {
            logger.error("Failed to get actual state for device '{}': URL cannot be created", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-url");
            return;
        }
        String action = "GetBinaryState";
        String variable = null;
        String actionService = BASICACTION;
        String value = null;
        String soapHeader = "\"urn:Belkin:service:" + actionService + ":1#" + action + "\"";
        String content = createStateRequestContent(action, actionService);
        try {
            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                    logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                    logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                    logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
                }
                value = substringBetween(wemoCallResponse, "<BinaryState>", "</BinaryState>");
                variable = "BinaryState";
                this.onValueReceived(variable, value, actionService + "1");
                value = substringBetween(wemoCallResponse, "<brightness>", "</brightness>");
                variable = "brightness";
                this.onValueReceived(variable, value, actionService + "1");
                value = substringBetween(wemoCallResponse, "<fader>", "</fader>");
                variable = "fader";
                this.onValueReceived(variable, value, actionService + "1");
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (Exception e) {
            logger.debug("Failed to get actual state for device '{}': {}", getThing().getUID(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
        action = "GetNightModeConfiguration";
        variable = null;
        value = null;
        soapHeader = "\"urn:Belkin:service:" + actionService + ":1#" + action + "\"";
        content = createStateRequestContent(action, actionService);
        try {
            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                    logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                    logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                    logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
                }
                value = substringBetween(wemoCallResponse, "<startTime>", "</startTime>");
                variable = "startTime";
                this.onValueReceived(variable, value, actionService + "1");
                value = substringBetween(wemoCallResponse, "<endTime>", "</endTime>");
                variable = "endTime";
                this.onValueReceived(variable, value, actionService + "1");
                value = substringBetween(wemoCallResponse, "<nightMode>", "</nightMode>");
                variable = "nightMode";
                this.onValueReceived(variable, value, actionService + "1");
                value = substringBetween(wemoCallResponse, "<nightModeBrightness>", "</nightModeBrightness>");
                variable = "nightModeBrightness";
                this.onValueReceived(variable, value, actionService + "1");
                updateStatus(ThingStatus.ONLINE);

            }
        } catch (Exception e) {
            logger.debug("Failed to get actual NightMode state for device '{}': {}", getThing().getUID(),
                    e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    public @Nullable State getDateTimeState(String attributeValue) {
        long value = 0;
        try {
            value = Long.parseLong(attributeValue);
        } catch (NumberFormatException e) {
            logger.error("Unable to parse attributeValue '{}' for device '{}'; expected long", attributeValue,
                    getThing().getUID());
            return null;
        }
        ZonedDateTime zoned = ZonedDateTime.ofInstant(Instant.ofEpochSecond(value), TimeZone.getDefault().toZoneId());
        State dateTimeState = new DateTimeType(zoned);
        logger.trace("New attribute brewed '{}' received", dateTimeState);
        return dateTimeState;
    }

    public void setBinaryState(String action, String argument, String value) {
        String localHost = getHost();
        if (localHost.isEmpty()) {
            logger.error("Failed to set binary state for device '{}': IP address missing", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-ip");
            return;
        }
        String wemoURL = getWemoURL(localHost, BASICACTION);
        if (wemoURL == null) {
            logger.error("Failed to set binary state for device '{}': URL cannot be created", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-url");
            return;
        }
        try {
            String soapHeader = "\"urn:Belkin:service:basicevent:1#SetBinaryState\"";
            String content = "<?xml version=\"1.0\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + "<u:" + action + " xmlns:u=\"urn:Belkin:service:basicevent:1\">" + "<" + argument
                    + ">" + value + "</" + argument + ">" + "</u:" + action + ">" + "</s:Body>" + "</s:Envelope>";

            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null && logger.isTraceEnabled()) {
                logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
            }
        } catch (Exception e) {
            logger.debug("Failed to set binaryState '{}' for device '{}': {}", value, getThing().getUID(),
                    e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    public void setTimerStart(String action, String argument, String value) {
        String localHost = getHost();
        if (localHost.isEmpty()) {
            logger.error("Failed to set timerStart for device '{}': IP address missing", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-ip");
            return;
        }
        String wemoURL = getWemoURL(localHost, BASICACTION);
        if (wemoURL == null) {
            logger.error("Failed to set timerStart for device '{}': URL cannot be created", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-url");
            return;
        }
        try {
            String soapHeader = "\"urn:Belkin:service:basicevent:1#SetBinaryState\"";
            String content = "<?xml version=\"1.0\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + "<u:SetBinaryState xmlns:u=\"urn:Belkin:service:basicevent:1\">" + value
                    + "</u:SetBinaryState>" + "</s:Body>" + "</s:Envelope>";
            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null && logger.isTraceEnabled()) {
                logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
            }
        } catch (Exception e) {
            logger.debug("Failed to set timerStart '{}' for device '{}': {}", value, getThing().getUID(),
                    e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }
}
