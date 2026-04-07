package io.example.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.example.application.BookingSlotEntity;
import io.example.application.BookingSlotEntity.Command.BookReservation;
import io.example.application.BookingSlotEntity.Command.MarkSlotAvailable;
import io.example.application.BookingSlotEntity.Command.UnmarkSlotAvailable;
import io.example.application.FlightConditionsAgent;
import io.example.application.ParticipantSlotsView;
import io.example.application.ParticipantSlotsView.ParticipantStatusInput;
import io.example.application.ParticipantSlotsView.SlotList;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/flight")
public class FlightEndpoint extends AbstractHttpEndpoint {
    private final Logger log = LoggerFactory.getLogger(FlightEndpoint.class);

    private final ComponentClient componentClient;

    public FlightEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    // Creates a new booking. All three identified participants will
    // be considered booked for the given timeslot, if they are all
    // "available" at the time of booking.
    @Post("/bookings/{slotId}")
    public HttpResponse createBooking(String slotId, BookingRequest request) {
        log.info("Creating booking for slot {}: {}", slotId, request);

        // Check flight conditions via the AI agent
        var sessionId = java.util.UUID.randomUUID().toString();
        var report = componentClient.forAgent()
                .inSession(sessionId)
                .method(FlightConditionsAgent::query)
                .invoke(slotId);

        if (!report.meetsRequirements()) {
            throw HttpException.badRequest(
                    "Flight conditions do not meet requirements for slot " + slotId);
        }

        // Conditions are acceptable, proceed with booking
        var cmd = new BookReservation(
                request.studentId(), request.aircraftId(),
                request.instructorId(), request.bookingId());

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::bookSlot)
                .invoke(cmd);

        return HttpResponses.created();
    }

    // Cancels an existing booking. Note that both the slot
    // ID and the booking ID are required.
    @Delete("/bookings/{slotId}/{bookingId}")
    public HttpResponse cancelBooking(String slotId, String bookingId) {
        log.info("Canceling booking id {}", bookingId);

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::cancelBooking)
                .invoke(bookingId);

        return HttpResponses.ok();
    }

    // Retrieves all slots in which a given participant has the supplied status.
    // Used to retrieve bookings and slots in which the participant is available
    @Get("/slots/{participantId}/{status}")
    public SlotList slotsByStatus(String participantId, String status) {
        return componentClient.forView()
                .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                .invoke(new ParticipantStatusInput(participantId, status));
    }

    // Returns the internal availability state for a given slot
    @Get("/availability/{slotId}")
    public Timeslot getSlot(String slotId) {
        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::getSlot)
                .invoke();
    }

    // Indicates that the supplied participant is available for booking
    // within the indicated time slot
    @Post("/availability/{slotId}")
    public HttpResponse markAvailable(String slotId, AvailabilityRequest request) {
        var participantType = parseParticipantType(request.participantType());
        log.info("Marking timeslot available for entity {}", slotId);

        var cmd = new MarkSlotAvailable(new Participant(request.participantId(), participantType));

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::markSlotAvailable)
                .invoke(cmd);

        return HttpResponses.ok();
    }

    // Unmarks a slot as available for the given participant.
    @Delete("/availability/{slotId}")
    public HttpResponse unmarkAvailable(String slotId, AvailabilityRequest request) {
        var participantType = parseParticipantType(request.participantType());

        var cmd = new UnmarkSlotAvailable(new Participant(request.participantId(), participantType));

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::unmarkSlotAvailable)
                .invoke(cmd);

        return HttpResponses.ok();
    }

    private ParticipantType parseParticipantType(String raw) {
        try {
            return ParticipantType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Bad participant type {}", raw);
            throw HttpException.badRequest("invalid participant type");
        }
    }

    // Public API representation of a booking request
    public record BookingRequest(
            String studentId, String aircraftId, String instructorId, String bookingId) {
    }

    // Public API representation of an availability mark/unmark request
    public record AvailabilityRequest(String participantId, String participantType) {
    }
}
