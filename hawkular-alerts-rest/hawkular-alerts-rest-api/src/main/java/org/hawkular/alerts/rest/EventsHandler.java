/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.rest;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import static org.hawkular.alerts.rest.HawkularAlertsApp.TENANT_HEADER_NAME;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.paging.Pager;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.EventsCriteria;
import org.hawkular.alerts.rest.ResponseUtil.ApiError;
import org.jboss.logging.Logger;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST endpoint for events
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Path("/events")
@Api(value = "/events", description = "Event Handling")
public class EventsHandler {
    private final Logger log = Logger.getLogger(EventsHandler.class);

    @HeaderParam(TENANT_HEADER_NAME)
    String tenantId;

    @EJB
    AlertsService alertsService;

    public EventsHandler() {
        log.debug("Creating instance.");
    }

    @POST
    @Path("/")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Create a new Event. " +
                    "Persist the new event and send it to the engine for processing/condition evaluation.",
            notes = "Returns created Event.",
            response = Event.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event Created."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class)
    })
    public Response createEvent(
            @ApiParam(value = "Event to be created. Category and Text fields required,",
                    name = "event", required = true)
            final Event event) {
        try {
            if (null != event) {
                if (isEmpty(event.getId())) {
                    return ResponseUtil.badRequest("Event with id null.");
                }
                if (isEmpty(event.getCategory())) {
                    return ResponseUtil.badRequest("Event with category null.");
                }
                event.setTenantId(tenantId);
                if (null != alertsService.getEvent(tenantId, event.getId(), true)) {
                    return ResponseUtil.badRequest("Event with ID [" + event.getId() + "] exists.");
                }
                if (!checkTags(event)) {
                    return ResponseUtil.badRequest("Tags " + event.getTags() + " must be non empty.");
                }
                /*
                    New events are sent directly to the engine for inference process.
                    Input events and new ones generated by the alerts engine are persisted at the end of the process.
                 */
                alertsService.addEvents(Collections.singletonList(event));
                if (log.isDebugEnabled()) {
                    log.debug("Event: " + event.toString());
                }
                return ResponseUtil.ok(event);
            } else {
                return ResponseUtil.badRequest("Event is null");
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    @POST
    @Path("/data")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Send events to the engine for processing/condition evaluation. " +
                    "Only events generated by the engine are persisted." +
                    "Input events are treated as external data and those are not persisted into the system.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event Created."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class)
    })
    public Response sendEvents(
            @ApiParam(required = true, name = "datums", value = "Data to be processed by alerting.")
            final Collection<Event> events) {
        try {
            if (isEmpty(events)) {
                return ResponseUtil.badRequest("Events are empty");
            } else {
                events.stream().forEach(e -> e.setTenantId(tenantId));
                alertsService.sendEvents(events);
                if (log.isDebugEnabled()) {
                    log.debugf("Sent Events: %s", events);
                }
                return ResponseUtil.ok();
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    @PUT
    @Path("/tags")
    @Consumes(APPLICATION_JSON)
    @ApiOperation(value = "Add tags to existing Events.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Events tagged successfully."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class)
    })
    public Response addTags(
            @ApiParam(required = true, value = "Comma separated list of eventIds to tag.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = true, value = "Comma separated list of tags to add, "
                    + "each tag of format \'name|value\'.")
            @QueryParam("tags")
            final String tags) {
        try {
            if (!isEmpty(eventIds) || isEmpty(tags)) {
                // criteria just used for convenient type translation
                EventsCriteria c = buildCriteria(null, null, eventIds, null, null, tags, false);
                alertsService.addEventTags(tenantId, c.getEventIds(), c.getTags());
                if (log.isDebugEnabled()) {
                    log.debugf("Tagged alertIds:%s, %s", c.getEventIds(), c.getTags());
                }
                return ResponseUtil.ok();
            } else {
                return ResponseUtil.badRequest("EventIds and Tags required for adding tags");
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    @DELETE
    @Path("/tags")
    @Consumes(APPLICATION_JSON)
    @ApiOperation(value = "Remove tags from existing Events.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Events untagged successfully."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class)
    })
    public Response deleteTags(
            @ApiParam(required = true, value = "Comma separated list of eventIds to untag.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = true, value = "Comma separated list of tag names to remove.")
            @QueryParam("tagNames")
            final String tagNames) {
        try {
            if (!isEmpty(eventIds) || isEmpty(tagNames)) {
                Collection<String> ids = Arrays.asList(eventIds.split(","));
                Collection<String> tags = Arrays.asList(tagNames.split(","));
                alertsService.removeEventTags(tenantId, ids, tags);
                if (log.isDebugEnabled()) {
                    log.debugf("Untagged eventIds:%s, %s", ids, tags);
                }
                return ResponseUtil.ok();
            } else {
                return ResponseUtil.badRequest("EventIds and Tags required for removing tags");
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    @GET
    @Path("/")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get events with optional filtering.",
            response = Event.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successfully fetched list of events."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response findEvents(
            @ApiParam(required = false, value = "Filter out events created before this time, millisecond since epoch.")
            @QueryParam("startTime")
            final Long startTime,
            @ApiParam(required = false, value = "Filter out events created after this time, millisecond since epoch.")
            @QueryParam("endTime")
            final Long endTime,
            @ApiParam(required = false, value = "Filter out events for unspecified eventIds, " +
                    "comma separated list of event IDs.")
            @QueryParam("eventIds") final String eventIds,
            @ApiParam(required = false, value = "Filter out events for unspecified triggers, " +
                    "comma separated list of trigger IDs.")
            @QueryParam("triggerIds")
            final String triggerIds,
            @ApiParam(required = false, value = "Filter out events for unspecified categories, " +
                    "comma separated list of category values.")
            @QueryParam("categories")
            final String categories,
            @ApiParam(required = false, value = "Filter out events for unspecified tags, comma separated list of tags, "
                    + "each tag of format \'name|value\'. Specify \'*\' for value to match all values.")
            @QueryParam("tags")
            final String tags,
            @ApiParam(required = false, value = "Return only thin events, do not include: evalSets.")
            @QueryParam("thin")
            final Boolean thin,
            @Context
            final UriInfo uri) {
        Pager pager = RequestUtil.extractPaging(uri);
        try {
            EventsCriteria criteria = buildCriteria(startTime, endTime, eventIds, triggerIds, categories, tags, thin);
            Page<Event> eventPage = alertsService.getEvents(tenantId, criteria, pager);
            if (log.isDebugEnabled()) {
                log.debug("Events: " + eventPage);
            }
            if (isEmpty(eventPage)) {
                return ResponseUtil.ok(eventPage);
            }
            return ResponseUtil.paginatedOk(eventPage, uri);
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }


    @DELETE
    @Path("/{eventId}")
    @ApiOperation(value = "Delete an existing Event.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event deleted."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class),
            @ApiResponse(code = 404, message = "Event not found.", response = ApiError.class)
    })
    public Response deleteEvent(
            @ApiParam(required = true, value = "Event id to be deleted.")
            @PathParam("eventId")
            final String eventId) {
        try {
            EventsCriteria criteria = new EventsCriteria();
            criteria.setEventId(eventId);
            int numDeleted = alertsService.deleteEvents(tenantId, criteria);
            if (1 == numDeleted) {
                if (log.isDebugEnabled()) {
                    log.debug("EventId: " + eventId);
                }
                return ResponseUtil.ok();
            } else {
                return ResponseUtil.notFound("Event " + eventId + " doesn't exist for delete");
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            return ResponseUtil.internalError(e);
        }
    }

    @PUT
    @Path("/delete")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Delete events with optional filtering.",
            notes = "Return number of events deleted.",
            response = Integer.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters."),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response deleteEvents(
            @ApiParam(required = false, value = "Filter out events created before this time, millisecond since epoch.")
            @QueryParam("startTime")
            final Long startTime,
            @ApiParam(required = false, value = "Filter out events created after this time, millisecond since epoch.")
            @QueryParam("endTime")
            final Long endTime,
            @ApiParam(required = false, value = "Filter out events for unspecified eventIds, " +
                    "comma separated list of event IDs.") @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = false, value = "Filter out events for unspecified triggers, " +
                    "comma separated list of trigger IDs.")
            @QueryParam("triggerIds")
            final String triggerIds,
            @ApiParam(required = false, value = "Filter out events for unspecified categories, " +
                    "comma separated list of category values.") @QueryParam("categories")
            final String categories,
            @ApiParam(required = false, value = "Filter out events for unspecified tags, comma separated list of tags, "
                    + "each tag of format \'name|value\'. Specify \'*\' for value to match all values.")
            @QueryParam("tags")
            final String tags
            ) {
        try {
            EventsCriteria criteria = buildCriteria(startTime, endTime, eventIds, triggerIds, categories, tags, null);
            int numDeleted = alertsService.deleteEvents(tenantId, criteria);
            if (log.isDebugEnabled()) {
                log.debug("Events deleted: " + numDeleted);
            }
            return ResponseUtil.ok(numDeleted);
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    private EventsCriteria buildCriteria(Long startTime, Long endTime, String eventIds, String triggerIds,
            String categories, String tags, Boolean thin) {
        EventsCriteria criteria = new EventsCriteria();
        criteria.setStartTime(startTime);
        criteria.setEndTime(endTime);
        if (!isEmpty(eventIds)) {
            criteria.setEventIds(Arrays.asList(eventIds.split(",")));
        }
        if (!isEmpty(triggerIds)) {
            criteria.setTriggerIds(Arrays.asList(triggerIds.split(",")));
        }
        if (!isEmpty(categories)) {
            criteria.setCategories(Arrays.asList(categories.split(",")));
        }
        if (!isEmpty(tags)) {
            String[] tagTokens = tags.split(",");
            Map<String, String> tagsMap = new HashMap<>(tagTokens.length);
            for (String tagToken : tagTokens) {
                String[] fields = tagToken.split("\\|");
                if (fields.length == 2) {
                    tagsMap.put(fields[0], fields[1]);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Invalid Tag Criteria " + Arrays.toString(fields));
                    }
                }
            }
            criteria.setTags(tagsMap);
        }
        if (null != thin) {
            criteria.setThin(thin.booleanValue());
        }

        return criteria;
    }

    @GET
    @Path("/event/{eventId}")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get an existing Event.",
            response = Event.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event found."),
            @ApiResponse(code = 404, message = "Event not found.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response getEvent(
            @ApiParam(value = "Id of Event to be retrieved.", required = true)
            @PathParam("eventId")
            final String eventId,
            @ApiParam(required = false, value = "Return only a thin event, do not include: evalSets, dampening.")
            @QueryParam("thin")
            final Boolean thin) {
        try {
            Event found = alertsService.getEvent(tenantId, eventId, ((null == thin) ? false : thin.booleanValue()));
            if (found != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Event: " + found);
                }
                return ResponseUtil.ok(found);
            } else {
                return ResponseUtil.notFound("eventId: " + eventId + " not found");
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            return ResponseUtil.internalError(e);
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isEmpty(Collection collection) {
        return collection == null || collection.isEmpty();
    }

    private boolean isEmpty(Map map) {
        return map == null || map.isEmpty();
    }

    private boolean checkTags(Event event) {
        if (isEmpty(event.getTags())) {
            return true;
        }
        for (Map.Entry<String, String> entry : event.getTags().entrySet()) {
            if (isEmpty(entry.getKey()) || isEmpty(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
