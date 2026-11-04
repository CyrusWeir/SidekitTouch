#!/usr/bin/env python3
"""Probe the public 392Fan departure service and current NSW site's journey API. No token needed."""
import datetime
import json
import subprocess
import urllib.parse
from zoneinfo import ZoneInfo

BASE = "https://transportnsw.info/api/trip/v1"

def request(url, body=None):
    command = ["curl", "--fail-with-body", "--silent", "--show-error", "--max-time", "30", url]
    if body is not None:
        command += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    value = json.loads(result.stdout)
    if value.get("errors"):
        raise RuntimeError("NSW journey API returned errors")
    return value

now = datetime.datetime.now(ZoneInfo("Australia/Sydney"))
stops = request(BASE + "/stop-finder-request?" + urllib.parse.urlencode({"name": "Central", "type": "stop"}))["locations"]
origin = next(stop for stop in stops if "Central" in stop["name"] and stop["type"] == "stop")
destination = request(BASE + "/stop-finder-request?" + urllib.parse.urlencode({"name": "Town Hall", "type": "stop"}))["locations"][0]
query = "query($input:TripPlannerInput!){widgets{tripOptions(input:$input){id departureTime arrivalTime isRealTime legs{routeNumber mode departureTime arrivalTime}}}}"
journeys = request("https://transportnsw.info/api/graphql", {"query": query, "variables": {"input": {
    "tripMode": "public", "from": origin["id"], "to": destination["id"], "typeOrigin": origin["type"], "typeDestination": destination["type"], "departureDateTime": now.isoformat()
}}})["data"]["widgets"]["tripOptions"]
assert journeys and journeys[0]["legs"], "No journey legs returned"
parameters = {"name": "G203519", "type": "stop", "date": now.strftime("%Y%m%d"), "time": now.strftime("%H%M"), "depArrMacro": "dep", "depType": "stopEvents", "accessible": "null", "debug": "false"}
departures = request(BASE + "/departure-list-request?" + urllib.parse.urlencode(parameters))["stopEvents"]
for departure in departures:
    assert departure["departureTimePlanned"] and departure["transportation"]
print(json.dumps({"sydney_time": now.isoformat(), "matching_stops": len(stops), "journeys": len(journeys), "journey_routes": [leg.get("routeNumber") or "Walk" for leg in journeys[0]["legs"]], "departures": len(departures), "live_departure_estimates": sum(bool(d.get("departureTimeEstimated")) for d in departures)}, indent=2))
