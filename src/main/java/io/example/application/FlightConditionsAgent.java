package io.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;

/*
 * The flight conditions agent is responsible for making a determination about the flight
 * conditions for a given day and time. You will need to clearly define the success criteria
 * for the report and instruct the agent (in the system prompt) about the schema of
 * the results it must return (the ConditionsReport).
 *
 * Also be sure to provide clear instructions on how and when tools should be invoked
 * in order to generate results.
 *
 * Flight conditions criteria don't need to be exhaustive, but you should supply the
 * criteria so that an agent does not need to make an external HTTP call to query
 * the condition limits.
 */

@Component(id = "flight-conditions-agent")
public class FlightConditionsAgent extends Agent {

    public record ConditionsReport(String timeSlotId, Boolean meetsRequirements) {
    }

    private static final String SYSTEM_MESSAGE = """
            You are a flight safety evaluation agent for a flight training school.
            Your job is to determine whether weather conditions are safe for a VFR
            (Visual Flight Rules) training flight at a given time slot.

            The time slot ID follows the format YYYY-MM-DD-HH (e.g. 2025-12-10-10
            means December 10, 2025 at 10:00).

            To evaluate conditions, you MUST call the getWeatherForecast tool with
            the provided time slot ID. Do not guess or assume weather conditions.

            After receiving the weather data, evaluate it against these VFR minimums
            for a training flight:
              - Wind speed must be less than 25 knots
              - Wind gusts must be less than 35 knots
              - Visibility must be at least 3 statute miles
              - Cloud ceiling must be at least 1500 feet AGL
              - No active thunderstorms, icing conditions, or tornado warnings

            If the time slot is more than 7 days in the future and the weather
            forecast is unavailable or uncertain, you should conditionally approve
            the flight (meetsRequirements = true) since conditions cannot yet be
            reliably predicted.

            You MUST respond with a JSON object matching this exact schema:
            {
              "timeSlotId": "<the time slot ID provided>",
              "meetsRequirements": true or false
            }

            Set meetsRequirements to true ONLY if all criteria are met or the slot
            is too far in the future to predict. Set it to false if any criterion
            is violated.
            """.stripIndent();

    public Effect<ConditionsReport> query(String timeSlotId) {
        return effects().systemMessage(SYSTEM_MESSAGE)
                .userMessage("Evaluate the flight conditions for time slot: " + timeSlotId
                        + ". Call the getWeatherForecast tool with this time slot ID and then "
                        + "assess whether VFR training flight minimums are met.")
                .responseAs(ConditionsReport.class)
                .thenReply();
    }

    /*
     * You can choose to hard code the weather conditions for specific days or you
     * can actually
     * communicate with an external weather API. You should be able to get both
     * suitable weather
     * conditions and poor weather conditions from this tool function for testing.
     */
    @FunctionTool(description = "Queries the weather conditions as they are forecasted based on the time slot ID of the training session booking")
    private String getWeatherForecast(String timeSlotId) {
        // Parse the hour from the slot ID (format: YYYY-MM-DD-HH)
        String hour = "12";
        try {
            String[] parts = timeSlotId.split("-");
            if (parts.length >= 4) {
                hour = parts[3];
            }
        } catch (Exception e) {
            // default to good conditions
        }

        // Hour 03 returns bad weather for testing rejection
        if ("03".equals(hour) || "3".equals(hour)) {
            return """
                    Weather forecast for %s:
                    Wind: 35 knots gusting to 50 knots from 270 degrees
                    Visibility: 1/2 statute mile in heavy rain and fog
                    Ceiling: 200 feet overcast
                    Conditions: Active thunderstorms in the area, severe turbulence reported
                    SIGMET: Severe icing conditions between 2000 and 10000 feet
                    Advisory: All VFR flights strongly discouraged
                    """.formatted(timeSlotId);
        }

        // All other hours return good flying conditions
        return """
                Weather forecast for %s:
                Wind: 8 knots from 180 degrees
                Visibility: 10 statute miles
                Ceiling: 5500 feet scattered, 8000 feet broken
                Temperature: 18C / Dewpoint: 10C
                Conditions: Clear skies, no precipitation
                No active advisories, SIGMETs, or AIRMETs
                Remarks: Excellent VFR conditions for training flights
                """.formatted(timeSlotId);
    }
}

