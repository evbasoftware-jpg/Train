/**
 * Complete Google Apps Script Web App Handler for Train Live Location
 * Spreadsheet ID: 137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA
 *
 * ব্যবহারের নিয়ম:
 * 1) আপনার Google Apps Script প্রোজেক্টের Code.gs ফাইলটি খুলে পুরো এই কোডটি পেস্ট করে Save করুন।
 * 2) (ঐচ্ছিক - External Railway API ফলব্যাকের জন্য):
 *    Apps Script-এর Project Settings (⚙️) → Script Properties-এ নিচের যেকোনো একটি Key যোগ করুন:
 *      - RAILRADAR_API_KEY  (যদি RailRadar API Key থাকে)
 *      - RAPIDAPI_KEY       (যদি IRCTC RapidAPI Key থাকে, সাথে RAPIDAPI_HOST ঐচ্ছিক)
 *    Key না দিলেও শিট সার্চ, লাইভ GPS শেয়ারিং, অ্যাডস এবং অটো-সিঙ্ক সম্পূর্ণ কাজ করবে।
 * 3) Deploy → Manage deployments → Edit (পেন্সিল আইকন) → Version: "New version" সিলেক্ট করে Deploy করুন
 *    (তাহলে API_URL একই থাকবে)।
 */

var DEFAULT_TRAIN_SPREADSHEET_ID = '137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA';
var GPS_CACHE_TTL_SECONDS = 900; // 15 মিনিট

/* ==================== WEB APP ENTRY POINTS ==================== */

function doGet(e) {
  try {
    var p = (e && e.parameter) ? e.parameter : {};
    var action = String(p.action || '').trim().toLowerCase();
    var sheetId = String(p.sheetId || DEFAULT_TRAIN_SPREADSHEET_ID).trim();

    if (action === 'ads') {
      return jsonResponse_(handleGetAds_(sheetId));
    }
    if (action === 'between') {
      var fromCode = String(p.from || '').trim().toUpperCase();
      var toCode = String(p.to || '').trim().toUpperCase();
      return jsonResponse_(handleGetBetween_(sheetId, fromCode, toCode));
    }
    if (action === 'live') {
      var trainNo = String(p.trainNo || p.trainNumber || '').replace(/\D/g, '');
      return jsonResponse_(handleGetLive_(sheetId, trainNo));
    }
    if (action === 'schedule' || action === 'route') {
      var schedTrainNo = String(p.trainNo || p.trainNumber || '').replace(/\D/g, '');
      return jsonResponse_(handleGetSchedule_(sheetId, schedTrainNo));
    }

    var props = PropertiesService.getScriptProperties();
    var hasExternalApi = !!(props.getProperty('RAILRADAR_API_KEY') || props.getProperty('RAPIDAPI_KEY'));
    return jsonResponse_({
      success: true,
      status: 'ok',
      service: 'TrainLiveLocation',
      apiConfigured: hasExternalApi
    });
  } catch (err) {
    return jsonResponse_({
      success: false,
      error: String(err && err.message ? err.message : err)
    });
  }
}

function doPost(e) {
  try {
    var raw = (e && e.postData && e.postData.contents) ? e.postData.contents : '{}';
    var data = {};
    try {
      data = JSON.parse(raw);
    } catch (_) {
      data = (e && e.parameter) ? e.parameter : {};
    }
    var action = String(data.action || '').trim();

    if (action === 'syncSheetData') {
      return jsonResponse_(handleSyncSheetData_(data));
    }
    if (action === 'gps') {
      return jsonResponse_(handlePostGps_(data));
    }
    return jsonResponse_({ success: false, error: 'Unknown action: ' + action });
  } catch (err) {
    return jsonResponse_({
      success: false,
      error: String(err && err.message ? err.message : err)
    });
  }
}

/* ==================== GET HANDLERS ==================== */

function handleGetAds_(sheetId) {
  var ss = SpreadsheetApp.openById(sheetId);
  var sheet = ss.getSheetByName('ads') || ss.getSheetByName('Ads');
  if (!sheet || sheet.getLastRow() < 2) {
    return { success: true, ads: [] };
  }
  var disp = sheet.getDataRange().getDisplayValues();
  var headers = disp[0];
  var ads = [];
  for (var r = 1; r < disp.length; r++) {
    var row = disp[r];
    var empty = true;
    for (var c = 0; c < row.length; c++) {
      if (String(row[c] || '').trim()) { empty = false; break; }
    }
    if (empty) continue;
    var obj = {};
    for (var c2 = 0; c2 < headers.length; c2++) {
      var h = String(headers[c2] || '').trim();
      if (h) obj[h] = String(row[c2] || '').trim();
    }
    ads.push(obj);
  }
  return { success: true, ads: ads };
}

function handleGetBetween_(sheetId, fromCode, toCode) {
  if (!fromCode || !toCode || fromCode === toCode) {
    return { success: true, data: { trains: [] } };
  }
  var ss = SpreadsheetApp.openById(sheetId);
  var sheetTrains = searchBetweenFromSheets_(ss, fromCode, toCode);
  if (sheetTrains.length) {
    return { success: true, source: 'sheet', data: { trains: sheetTrains } };
  }

  // শিটে না পেলে External Railway API Key দিয়ে সার্চ ও অটো-সেভ
  var apiTrains = fetchBetweenFromExternalApi_(fromCode, toCode);
  if (apiTrains.length) {
    try {
      var trainItems = [];
      for (var i = 0; i < apiTrains.length; i++) {
        var tr = apiTrains[i];
        trainItems.push({
          trainNumber: tr.train.number,
          trainName: tr.train.name,
          fromCode: fromCode,
          fromName: fromCode,
          departure: tr.from.departure !== '--:--' ? tr.from.departure : '',
          toCode: toCode,
          toName: toCode,
          arrival: tr.to.arrival !== '--:--' ? tr.to.arrival : '',
          distanceKm: Number(tr.distance) || 0,
          trainStops: fromCode + ', ' + toCode
        });
      }
      upsertTrainSheet_(ss.getSheetByName('Train'), trainItems, true);
    } catch (_) {}
    return { success: true, source: 'api', data: { trains: apiTrains } };
  }

  return { success: true, data: { trains: [] } };
}

function handleGetSchedule_(sheetId, trainNo) {
  if (!trainNo) return { success: false, error: 'Missing trainNo' };
  var ss = SpreadsheetApp.openById(sheetId);
  var info = loadTrainAndRouteFromSheets_(ss, trainNo);
  if (info.route.length >= 2) {
    return { success: true, source: 'sheet', trainName: info.trainName, route: info.route };
  }
  var apiLive = fetchLiveAndRouteFromExternalApi_(trainNo);
  if (apiLive && apiLive.route && apiLive.route.length) {
    syncExternalTrainToSheets_(ss, trainNo, apiLive.trainName || info.trainName, apiLive.route);
    return { success: true, source: 'api', trainName: apiLive.trainName || info.trainName, route: apiLive.route };
  }
  return { success: true, source: 'sheet', trainName: info.trainName, route: info.route };
}

function handleGetLive_(sheetId, trainNo) {
  if (!trainNo) return { success: false, error: 'Missing trainNo' };
  var ss = SpreadsheetApp.openById(sheetId);
  var info = loadTrainAndRouteFromSheets_(ss, trainNo);
  var gpsLoc = getFreshGps_(trainNo);

  var apiLive = null;
  if (!gpsLoc || info.route.length < 2) {
    apiLive = fetchLiveAndRouteFromExternalApi_(trainNo);
    if (apiLive && apiLive.route && apiLive.route.length > info.route.length) {
      info.route = apiLive.route;
      if (apiLive.trainName) info.trainName = apiLive.trainName;
      syncExternalTrainToSheets_(ss, trainNo, info.trainName, info.route);
    }
  }

  var currentLocation = null;
  var source = null;

  if (gpsLoc) {
    currentLocation = buildLocationFromGps_(gpsLoc, info.route);
    source = 'gps';
  } else if (apiLive && apiLive.currentLocation) {
    currentLocation = apiLive.currentLocation;
    source = 'api';
  } else if (info.route.length) {
    currentLocation = estimateLocationFromRoute_(info.route);
    source = 'sheet';
  }

  return {
    success: true,
    data: {
      trainName: info.trainName || (apiLive && apiLive.trainName) || '',
      source: source,
      currentLocation: currentLocation,
      route: info.route
    }
  };
}

/* ==================== GPS POST / CACHE ==================== */

function handlePostGps_(data) {
  var trainNo = String(data.trainNo || data.trainNumber || '').replace(/\D/g, '');
  var lat = Number(data.lat);
  var lon = Number(data.lon);
  if (!trainNo || !isFinite(lat) || !isFinite(lon)) {
    return { success: false, error: 'Invalid GPS payload' };
  }
  var key = 'gps_' + normTrainKey_(trainNo);
  var record = {
    trainNo: trainNo,
    trainName: String(data.trainName || ''),
    lat: lat,
    lon: lon,
    speedKmh: (data.speed != null && isFinite(Number(data.speed))) ? Math.round(Number(data.speed) * 3.6 * 10) / 10 : 0,
    bearingDegrees: (data.heading != null && isFinite(Number(data.heading))) ? Number(data.heading) : null,
    time: Number(data.time) || Date.now()
  };
  CacheService.getScriptCache().put(key, JSON.stringify(record), GPS_CACHE_TTL_SECONDS);
  return { success: true };
}

function getFreshGps_(trainNo) {
  var key = 'gps_' + normTrainKey_(trainNo);
  var raw = CacheService.getScriptCache().get(key);
  if (!raw) return null;
  try {
    var rec = JSON.parse(raw);
    if (!rec || !isFinite(rec.lat) || !isFinite(rec.lon)) return null;
    if (Date.now() - (Number(rec.time) || 0) > GPS_CACHE_TTL_SECONDS * 1000) return null;
    return rec;
  } catch (_) {
    return null;
  }
}

/* ==================== SHEET SEARCH & ROUTE BUILDERS ==================== */

function searchBetweenFromSheets_(ss, fromCode, toCode) {
  var stCoords = readStationMap_(ss.getSheetByName('Station'));
  var schedSheet = ss.getSheetByName('Schedule');
  var trainSheet = ss.getSheetByName('Train');

  var byTrain = {};

  // 1) Schedule শিট (শুধুমাত্র কমার্শিয়াল স্টপেজ — 0-মিনিট পাস-থ্রু বাদ দিয়ে)
  if (schedSheet && schedSheet.getLastRow() >= 2) {
    var sDisp = schedSheet.getDataRange().getDisplayValues();
    var shm = headerMap_(sDisp[0]);
    var cId = shm.scheduleid != null ? shm.scheduleid : 0;
    var cNo = shm.trainnumber != null ? shm.trainnumber : 1;
    var cName = shm.trainname != null ? shm.trainname : 2;
    var cSt = shm.stationcode != null ? shm.stationcode : 3;
    var cArr = shm.arrival != null ? shm.arrival : 5;
    var cDep = shm.departure != null ? shm.departure : 6;
    var cDay = shm.day != null ? shm.day : 7;

    var grouped = {};
    for (var r = 1; r < sDisp.length; r++) {
      var stC = String(sDisp[r][cSt] || '').trim().toUpperCase();
      if (stC !== fromCode && stC !== toCode) continue;
      var tNoRaw = String(sDisp[r][cNo] || '').replace(/\D/g, '');
      var tk = normTrainKey_(tNoRaw);
      if (!tk) continue;
      var arrStr = normHHmm_(sDisp[r][cArr]);
      var depStr = normHHmm_(sDisp[r][cDep]);
      if (!isScheduledStopTimes_(arrStr, depStr)) continue;
      if (!grouped[tk]) grouped[tk] = { num: tNoRaw, name: '', from: null, to: null };
      var rowObj = {
        id: Number(sDisp[r][cId]) || r,
        name: String(sDisp[r][cName] || '').trim(),
        arr: arrStr,
        dep: depStr,
        day: parseInt(sDisp[r][cDay], 10) || 1,
        order: r
      };
      if (!grouped[tk].name && rowObj.name) grouped[tk].name = rowObj.name;
      if (stC === fromCode && !grouped[tk].from) grouped[tk].from = rowObj;
      if (stC === toCode && !grouped[tk].to) grouped[tk].to = rowObj;
    }

    var fGeo = stCoords[fromCode];
    var tGeo = stCoords[toCode];
    var approxDist = (fGeo && tGeo && fGeo.lat != null && tGeo.lat != null)
      ? Math.round(haversineKm_(fGeo.lat, fGeo.lon, tGeo.lat, tGeo.lon) * 1.2 * 10) / 10
      : 0;

    for (var k in grouped) {
      var g = grouped[k];
      if (!g.from || !g.to) continue;
      var fm = (g.from.day - 1) * 1440 + (parseTimeMins_(g.from.dep || g.from.arr) || 0);
      var tm = (g.to.day - 1) * 1440 + (parseTimeMins_(g.to.arr || g.to.dep) || 0);
      if (tm <= fm && !(g.to.id > g.from.id && (g.to.day === g.from.day && fm === tm))) continue;
      byTrain[k] = {
        train: { number: g.num, name: g.name },
        from: { departure: g.from.dep || g.from.arr || '--:--', stationCode: fromCode },
        to: { arrival: g.to.arr || g.to.dep || '--:--', stationCode: toCode },
        distance: approxDist
      };
    }
  }

  // 2) Train শিটের নাম ও সরাসরি / Train_Stops ম্যাচ
  if (trainSheet && trainSheet.getLastRow() >= 2) {
    var tDisp = trainSheet.getDataRange().getDisplayValues();
    var thm = headerMap_(tDisp[0]);
    var tcNum = thm.trainnumber != null ? thm.trainnumber : 0;
    var tcName = thm.trainname != null ? thm.trainname : 1;
    var tcFrom = thm.fromstationcode != null ? thm.fromstationcode : 4;
    var tcDep = thm.departure != null ? thm.departure : 6;
    var tcTo = thm.tostationcode != null ? thm.tostationcode : 7;
    var tcArr = thm.arrival != null ? thm.arrival : 9;
    var tcDist = thm.distancekm != null ? thm.distancekm : 12;
    var tcStops = thm.trainstops != null ? thm.trainstops : 21;

    for (var r2 = 1; r2 < tDisp.length; r2++) {
      var row = tDisp[r2];
      var num = String(row[tcNum] || '').replace(/\D/g, '');
      var tk2 = normTrainKey_(num);
      if (!tk2) continue;
      var tName = String(row[tcName] || '').trim();
      if (byTrain[tk2]) {
        if (!byTrain[tk2].train.name && tName) byTrain[tk2].train.name = tName;
        continue;
      }
      var stopsRaw = String(row[tcStops] || '').trim();
      var tokens = stopsRaw ? stopsRaw.split(/[,;|\n\r]+|->|→/) : [];
      var fc = String(row[tcFrom] || '').trim().toUpperCase();
      var tc = String(row[tcTo] || '').trim().toUpperCase();
      var codes = [];
      for (var i = 0; i < tokens.length; i++) {
        var cTok = extractStopCode_(tokens[i]);
        if (cTok) codes.push(cTok);
      }
      if (!codes.length && fc && tc) codes = [fc, tc];
      else {
        if (fc && (!codes.length || codes[0] !== fc)) codes.unshift(fc);
        if (tc && (!codes.length || codes[codes.length - 1] !== tc)) codes.push(tc);
      }
      var fIdx = codes.indexOf(fromCode);
      var tIdx = fIdx >= 0 ? codes.indexOf(toCode, fIdx + 1) : -1;
      if (fIdx >= 0 && tIdx > fIdx) {
        var dep2 = (fIdx === 0 ? normHHmm_(row[tcDep]) : null) || '--:--';
        var arr2 = (tIdx === codes.length - 1 ? normHHmm_(row[tcArr]) : null) || '--:--';
        var dist2 = (fIdx === 0 && tIdx === codes.length - 1) ? (Number(row[tcDist]) || 0) : 0;
        byTrain[tk2] = {
          train: { number: num, name: tName },
          from: { departure: dep2, stationCode: fromCode },
          to: { arrival: arr2, stationCode: toCode },
          distance: dist2
        };
      }
    }
  }

  var out = [];
  for (var key in byTrain) out.push(byTrain[key]);
  out.sort(function (a, b) {
    var ma = parseTimeMins_(a.from.departure);
    var mb = parseTimeMins_(b.from.departure);
    if (ma == null) ma = 9999;
    if (mb == null) mb = 9999;
    return ma !== mb ? ma - mb : String(a.train.number).localeCompare(String(b.train.number));
  });
  return out;
}

function loadTrainAndRouteFromSheets_(ss, trainNo) {
  var targetKey = normTrainKey_(trainNo);
  var stMap = readStationMap_(ss.getSheetByName('Station'));
  var trainName = '';
  var schedRows = [];

  var trainSheet = ss.getSheetByName('Train');
  if (trainSheet && trainSheet.getLastRow() >= 2) {
    var tDisp = trainSheet.getDataRange().getDisplayValues();
    var thm = headerMap_(tDisp[0]);
    var tcNum = thm.trainnumber != null ? thm.trainnumber : 0;
    var tcName = thm.trainname != null ? thm.trainname : 1;
    for (var r = 1; r < tDisp.length; r++) {
      if (normTrainKey_(tDisp[r][tcNum]) === targetKey) {
        trainName = String(tDisp[r][tcName] || '').trim();
        break;
      }
    }
  }

  var schedSheet = ss.getSheetByName('Schedule');
  if (schedSheet && schedSheet.getLastRow() >= 2) {
    var sDisp = schedSheet.getDataRange().getDisplayValues();
    var shm = headerMap_(sDisp[0]);
    var cId = shm.scheduleid != null ? shm.scheduleid : 0;
    var cNo = shm.trainnumber != null ? shm.trainnumber : 1;
    var cName = shm.trainname != null ? shm.trainname : 2;
    var cSt = shm.stationcode != null ? shm.stationcode : 3;
    var cStName = shm.stationname != null ? shm.stationname : 4;
    var cArr = shm.arrival != null ? shm.arrival : 5;
    var cDep = shm.departure != null ? shm.departure : 6;
    var cDay = shm.day != null ? shm.day : 7;

    for (var r2 = 1; r2 < sDisp.length; r2++) {
      if (normTrainKey_(sDisp[r2][cNo]) !== targetKey) continue;
      if (!trainName && sDisp[r2][cName]) trainName = String(sDisp[r2][cName]).trim();
      var code = String(sDisp[r2][cSt] || '').trim().toUpperCase();
      var arr = normHHmm_(sDisp[r2][cArr]);
      var dep = normHHmm_(sDisp[r2][cDep]);
      schedRows.push({
        id: Number(sDisp[r2][cId]) || r2,
        code: code,
        name: String(sDisp[r2][cStName] || '').trim(),
        arr: arr,
        dep: dep,
        day: parseInt(sDisp[r2][cDay], 10) || 1,
        order: r2,
        isStop: isScheduledStopTimes_(arr, dep)
      });
    }
  }

  schedRows.sort(function (a, b) {
    if (a.day !== b.day) return a.day - b.day;
    var ma = parseTimeMins_(a.arr || a.dep);
    var mb = parseTimeMins_(b.arr || b.dep);
    if (ma == null) ma = 99999;
    if (mb == null) mb = 99999;
    if (ma !== mb) return ma - mb;
    return a.id !== b.id ? a.id - b.id : a.order - b.order;
  });

  var stopping = [];
  for (var i = 0; i < schedRows.length; i++) {
    if (schedRows[i].isStop) stopping.push(schedRows[i]);
  }
  var effective = stopping.length >= 2 ? stopping : schedRows;

  var route = [];
  for (var j = 0; j < effective.length; j++) {
    var sr = effective[j];
    var stGeo = stMap[sr.code] || {};
    var sched = sr.arr || sr.dep || '--:--';
    route.push({
      stationCode: sr.code,
      stationName: sr.name || stGeo.name || sr.code,
      sequence: j + 1,
      lat: stGeo.lat != null ? stGeo.lat : null,
      lon: stGeo.lon != null ? stGeo.lon : null,
      scheduledTime: sched,
      arrival: sr.arr || sched,
      departure: sr.dep || sched
    });
  }

  return { trainName: trainName, route: route };
}

function readStationMap_(sheet) {
  var map = {};
  if (!sheet || sheet.getLastRow() < 2) return map;
  var vals = sheet.getDataRange().getValues();
  var hm = headerMap_(vals[0]);
  var cCode = hm.stationcode != null ? hm.stationcode : 0;
  var cName = hm.stationname != null ? hm.stationname : 1;
  var cLat = hm.latitude != null ? hm.latitude : 5;
  var cLon = hm.longitude != null ? hm.longitude : 6;
  for (var r = 1; r < vals.length; r++) {
    var c = String(vals[r][cCode] || '').trim().toUpperCase();
    if (!c) continue;
    var lat = Number(vals[r][cLat]);
    var lon = Number(vals[r][cLon]);
    map[c] = {
      name: String(vals[r][cName] || '').trim(),
      lat: (vals[r][cLat] !== '' && vals[r][cLat] != null && isFinite(lat)) ? lat : null,
      lon: (vals[r][cLon] !== '' && vals[r][cLon] != null && isFinite(lon)) ? lon : null
    };
  }
  return map;
}

/* ==================== OPTIONAL EXTERNAL RAILWAY API FALLBACK ==================== */

function fetchBetweenFromExternalApi_(fromCode, toCode) {
  var props = PropertiesService.getScriptProperties();
  var rapidKey = props.getProperty('RAPIDAPI_KEY');
  var railRadarKey = props.getProperty('RAILRADAR_API_KEY');
  var out = [];

  if (railRadarKey) {
    try {
      var rrUrl = 'https://railradar.in/api/v1/trains/between?from=' + encodeURIComponent(fromCode) + '&to=' + encodeURIComponent(toCode);
      var rrResp = UrlFetchApp.fetch(rrUrl, {
        headers: { 'x-api-key': railRadarKey, 'Accept': 'application/json' },
        muteHttpExceptions: true
      });
      if (rrResp.getResponseCode() === 200) {
        var rrJson = JSON.parse(rrResp.getContentText() || '{}');
        var list = (rrJson.data && rrJson.data.trains) ? rrJson.data.trains : (rrJson.trains || []);
        for (var i = 0; i < list.length; i++) {
          var item = list[i];
          var num = String((item.train && item.train.number) || item.trainNumber || item.train_number || '').replace(/\D/g, '');
          if (!num) continue;
          out.push({
            train: { number: num, name: String((item.train && item.train.name) || item.trainName || item.train_name || '') },
            from: { departure: normHHmm_((item.from && item.from.departure) || item.from_std || item.departure) || '--:--', stationCode: fromCode },
            to: { arrival: normHHmm_((item.to && item.to.arrival) || item.to_sta || item.arrival) || '--:--', stationCode: toCode },
            distance: Number(item.distance || item.distanceKm) || 0
          });
        }
        if (out.length) return out;
      }
    } catch (_) {}
  }

  if (rapidKey) {
    try {
      var host = props.getProperty('RAPIDAPI_HOST') || 'irctc1.p.rapidapi.com';
      var url = 'https://' + host + '/api/v3/trainBetweenStations?fromStationCode=' + encodeURIComponent(fromCode) + '&toStationCode=' + encodeURIComponent(toCode);
      var resp = UrlFetchApp.fetch(url, {
        headers: { 'x-rapidapi-key': rapidKey, 'x-rapidapi-host': host },
        muteHttpExceptions: true
      });
      if (resp.getResponseCode() === 200) {
        var json = JSON.parse(resp.getContentText() || '{}');
        var arr = json.data || [];
        for (var j = 0; j < arr.length; j++) {
          var t = arr[j];
          var tNo = String(t.train_number || t.trainNumber || '').replace(/\D/g, '');
          if (!tNo) continue;
          out.push({
            train: { number: tNo, name: String(t.train_name || t.trainName || '') },
            from: { departure: normHHmm_(t.from_std || t.from_time) || '--:--', stationCode: fromCode },
            to: { arrival: normHHmm_(t.to_sta || t.to_time) || '--:--', stationCode: toCode },
            distance: Number(t.distance) || 0
          });
        }
      }
    } catch (_) {}
  }

  return out;
}

function fetchLiveAndRouteFromExternalApi_(trainNo) {
  var props = PropertiesService.getScriptProperties();
  var railRadarKey = props.getProperty('RAILRADAR_API_KEY');
  var rapidKey = props.getProperty('RAPIDAPI_KEY');

  if (railRadarKey) {
    try {
      var rrUrl = 'https://railradar.in/api/v1/trains/' + encodeURIComponent(trainNo) + '/live';
      var rrResp = UrlFetchApp.fetch(rrUrl, {
        headers: { 'x-api-key': railRadarKey, 'Accept': 'application/json' },
        muteHttpExceptions: true
      });
      if (rrResp.getResponseCode() === 200) {
        var rrJson = JSON.parse(rrResp.getContentText() || '{}');
        var d = rrJson.data || rrJson;
        if (d && (d.route || d.currentLocation)) {
          return {
            trainName: String(d.trainName || (d.train && d.train.name) || ''),
            currentLocation: d.currentLocation || null,
            route: normalizeExternalRoute_(d.route || [])
          };
        }
      }
    } catch (_) {}
  }

  if (rapidKey) {
    try {
      var host = props.getProperty('RAPIDAPI_HOST') || 'irctc1.p.rapidapi.com';
      var sUrl = 'https://' + host + '/api/v1/getTrainSchedule?trainNo=' + encodeURIComponent(trainNo);
      var sResp = UrlFetchApp.fetch(sUrl, {
        headers: { 'x-rapidapi-key': rapidKey, 'x-rapidapi-host': host },
        muteHttpExceptions: true
      });
      if (sResp.getResponseCode() === 200) {
        var sj = JSON.parse(sResp.getContentText() || '{}');
        var sd = sj.data || sj;
        var rawRoute = sd.route || [];
        if (rawRoute.length) {
          return {
            trainName: String(sd.trainName || ''),
            currentLocation: null,
            route: normalizeExternalRoute_(rawRoute)
          };
        }
      }
    } catch (_) {}
  }

  return null;
}

function normalizeExternalRoute_(rawRoute) {
  var out = [];
  for (var i = 0; i < rawRoute.length; i++) {
    var st = rawRoute[i];
    var code = String(st.stationCode || st.station_code || st.code || '').trim().toUpperCase();
    var name = String(st.stationName || st.station_name || st.name || code).trim();
    if (!code && !name) continue;
    var arr = normHHmm_(st.arrival || st.std_arr || st.scheduledArrival) || null;
    var dep = normHHmm_(st.departure || st.std_dep || st.scheduledDeparture) || null;
    var sched = normHHmm_(st.scheduledTime) || arr || dep || '--:--';
    out.push({
      stationCode: code,
      stationName: name,
      sequence: i + 1,
      lat: (st.lat != null && isFinite(Number(st.lat))) ? Number(st.lat) : ((st.latitude != null && isFinite(Number(st.latitude))) ? Number(st.latitude) : null),
      lon: (st.lon != null && isFinite(Number(st.lon))) ? Number(st.lon) : ((st.longitude != null && isFinite(Number(st.longitude))) ? Number(st.longitude) : null),
      scheduledTime: sched,
      arrival: arr || sched,
      departure: dep || sched
    });
  }
  return out;
}

function syncExternalTrainToSheets_(ss, trainNo, trainName, route) {
  if (!route || route.length < 2) return;
  try {
    var stopsArr = [], stations = [], sched = [];
    var day = 1, prevMin = null;
    for (var i = 0; i < route.length; i++) {
      var s = route[i];
      if (s.stationCode) stopsArr.push(s.stationCode);
      if (s.stationCode) {
        var stObj = { stationCode: s.stationCode, stationName: s.stationName || s.stationCode };
        if (s.lat != null && s.lon != null) { stObj.latitude = s.lat; stObj.longitude = s.lon; }
        stations.push(stObj);
      }
      var minNow = parseTimeMins_(s.arrival || s.scheduledTime || s.departure);
      if (prevMin != null && minNow != null && minNow + 120 < prevMin) day++;
      if (minNow != null) prevMin = minNow;
      sched.push({
        sequence: i + 1,
        trainNumber: trainNo,
        trainName: trainName || '',
        stationCode: s.stationCode,
        stationName: s.stationName || s.stationCode,
        arrival: s.arrival || s.scheduledTime || 'None',
        departure: s.departure || s.scheduledTime || 'None',
        day: day
      });
    }
    if (stations.length) upsertStationsSheet_(ss.getSheetByName('Station'), stations);
    upsertTrainSheet_(ss.getSheetByName('Train'), [{
      trainNumber: trainNo,
      trainName: trainName || '',
      fromCode: route[0].stationCode || '',
      fromName: route[0].stationName || '',
      departure: route[0].departure || route[0].scheduledTime || '',
      toCode: route[route.length - 1].stationCode || '',
      toName: route[route.length - 1].stationName || '',
      arrival: route[route.length - 1].arrival || route[route.length - 1].scheduledTime || '',
      routePointsCount: route.length,
      trainStops: stopsArr.join(', ')
    }], false);
    upsertScheduleSheet_(ss.getSheetByName('Schedule'), sched);
  } catch (_) {}
}

function buildLocationFromGps_(gps, route) {
  var bestIdx = 0, bestDist = 1e9;
  for (var i = 0; i < route.length; i++) {
    if (route[i].lat != null && route[i].lon != null) {
      var d = haversineKm_(gps.lat, gps.lon, route[i].lat, route[i].lon);
      if (d < bestDist) { bestDist = d; bestIdx = i; }
    }
  }
  var cur = route[bestIdx] || {};
  var nxt = route[bestIdx + 1] || null;
  return {
    stationCode: cur.stationCode || null,
    stationName: cur.stationName || 'Train',
    sequence: cur.sequence || (bestIdx + 1),
    lat: gps.lat,
    lon: gps.lon,
    speedKmh: gps.speedKmh || 0,
    bearingDegrees: gps.bearingDegrees,
    segmentProgress: 0,
    nextStationName: nxt ? (nxt.stationName || nxt.stationCode) : null
  };
}

function estimateLocationFromRoute_(route) {
  if (!route.length) return null;
  var cur = route[0];
  var nxt = route[1] || null;
  return {
    stationCode: cur.stationCode || null,
    stationName: cur.stationName || cur.stationCode || '',
    sequence: 1,
    lat: cur.lat,
    lon: cur.lon,
    speedKmh: 0,
    bearingDegrees: null,
    segmentProgress: 0,
    nextStationName: nxt ? (nxt.stationName || nxt.stationCode) : null
  };
}

/* ==================== AUTO-SYNC (POST action=syncSheetData) ==================== */

function handleSyncSheetData_(data) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) {
    return { success: false, error: 'Busy' };
  }
  try {
    var sheetId = (data && data.sheetId) ? String(data.sheetId) : DEFAULT_TRAIN_SPREADSHEET_ID;
    var ss = SpreadsheetApp.openById(sheetId);

    var stats = { trainsUpdated: 0, trainsAdded: 0, stationsUpserted: 0, scheduleUpserted: 0 };

    if (Array.isArray(data.stations) && data.stations.length) {
      stats.stationsUpserted = upsertStationsSheet_(ss.getSheetByName('Station'), data.stations);
    }

    var trainSheet = ss.getSheetByName('Train');
    if (data.train && data.train.trainNumber) {
      var res = upsertTrainSheet_(trainSheet, [data.train], false);
      stats.trainsUpdated += res.updated;
      stats.trainsAdded += res.added;
    }
    if (Array.isArray(data.betweenTrains) && data.betweenTrains.length) {
      var bRes = upsertTrainSheet_(trainSheet, data.betweenTrains, true);
      stats.trainsUpdated += bRes.updated;
      stats.trainsAdded += bRes.added;
    }

    if (Array.isArray(data.schedule) && data.schedule.length) {
      stats.scheduleUpserted = upsertScheduleSheet_(ss.getSheetByName('Schedule'), data.schedule);
    }

    return { success: true, stats: stats };
  } catch (err) {
    return { success: false, error: String(err && err.message ? err.message : err) };
  } finally {
    lock.releaseLock();
  }
}

function upsertStationsSheet_(sheet, stations) {
  if (!sheet || !stations.length) return 0;
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 7);
  var values = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol).getValues() : [];
  if (!values.length) {
    values = [['Station_Code', 'Station_Name', 'State', 'Zone', 'Address', 'Latitude', 'Longitude']];
    sheet.getRange(1, 1, 1, 7).setValues(values);
    lastRow = 1;
  }
  var hm = headerMap_(values[0]);
  var cCode = hm.stationcode != null ? hm.stationcode : 0;
  var cName = hm.stationname != null ? hm.stationname : 1;
  var cLat = hm.latitude != null ? hm.latitude : 5;
  var cLon = hm.longitude != null ? hm.longitude : 6;

  var rowByCode = {};
  for (var r = 1; r < values.length; r++) {
    var code = String(values[r][cCode] || '').trim().toUpperCase();
    if (code) rowByCode[code] = r;
  }

  var touched = 0;
  var toAppend = [];
  for (var i = 0; i < stations.length; i++) {
    var st = stations[i];
    var codeKey = String(st.stationCode || '').trim().toUpperCase();
    if (!codeKey) continue;
    var rowIdx = rowByCode[codeKey];
    var hasCoords = st.latitude != null && st.longitude != null &&
      isFinite(Number(st.latitude)) && isFinite(Number(st.longitude));

    if (rowIdx != null) {
      var row = values[rowIdx];
      var changed = false;
      if (st.stationName && (isEmptyOrNone_(row[cName]) || String(row[cName]).trim().toUpperCase() === codeKey)) {
        if (String(row[cName]).trim() !== st.stationName) { row[cName] = st.stationName; changed = true; }
      }
      if (hasCoords) {
        var curLat = Number(row[cLat]), curLon = Number(row[cLon]);
        var newLat = Number(st.latitude), newLon = Number(st.longitude);
        if (row[cLat] === '' || row[cLat] == null || row[cLon] === '' || row[cLon] == null ||
            !isFinite(curLat) || !isFinite(curLon) ||
            Math.abs(curLat - newLat) > 0.005 || Math.abs(curLon - newLon) > 0.005) {
          row[cLat] = newLat;
          row[cLon] = newLon;
          changed = true;
        }
      }
      if (changed) {
        sheet.getRange(rowIdx + 1, 1, 1, lastCol).setValues([row]);
        touched++;
      }
    } else {
      var newRow = blankRow_(lastCol);
      newRow[cCode] = codeKey;
      newRow[cName] = st.stationName || codeKey;
      if (hasCoords) {
        newRow[cLat] = Number(st.latitude);
        newRow[cLon] = Number(st.longitude);
      }
      rowByCode[codeKey] = values.length + toAppend.length;
      toAppend.push(newRow);
      touched++;
    }
  }
  if (toAppend.length) {
    sheet.getRange(lastRow + 1, 1, toAppend.length, lastCol).setValues(toAppend);
  }
  return touched;
}

function upsertTrainSheet_(sheet, trainItems, onlyInsertIfMissingOrFillBlank) {
  if (!sheet || !trainItems.length) return { updated: 0, added: 0 };
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 22);
  var range = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol) : null;
  var values = range ? range.getValues() : [];
  var disp = range ? range.getDisplayValues() : [];

  if (!values.length) {
    var header = [[
      'Train_Number', 'Train_Name', 'Train_Type', 'Zone',
      'From_Station_Code', 'From_Station_Name', 'Departure',
      'To_Station_Code', 'To_Station_Name', 'Arrival',
      'Duration_Hours', 'Duration_Minutes', 'Distance_KM', 'Return_Train',
      'First_AC', 'Second_AC', 'Third_AC', 'Chair_Car', 'First_Class', 'Sleeper',
      'Route_Points_Count', 'Train_Stops'
    ]];
    sheet.getRange(1, 1, 1, 22).setValues(header);
    values = header;
    disp = header;
    lastRow = 1;
  }

  var hm = headerMap_(disp[0]);
  var cNum = hm.trainnumber != null ? hm.trainnumber : 0;
  var cName = hm.trainname != null ? hm.trainname : 1;
  var cFromC = hm.fromstationcode != null ? hm.fromstationcode : 4;
  var cFromN = hm.fromstationname != null ? hm.fromstationname : 5;
  var cDep = hm.departure != null ? hm.departure : 6;
  var cToC = hm.tostationcode != null ? hm.tostationcode : 7;
  var cToN = hm.tostationname != null ? hm.tostationname : 8;
  var cArr = hm.arrival != null ? hm.arrival : 9;
  var cDist = hm.distancekm != null ? hm.distancekm : 12;
  var cPts = hm.routepointscount != null ? hm.routepointscount : 20;
  var cStops = hm.trainstops != null ? hm.trainstops : 21;

  var rowByTrain = {};
  for (var r = 1; r < disp.length; r++) {
    var k = normTrainKey_(disp[r][cNum]);
    if (k) rowByTrain[k] = r;
  }

  var updated = 0, added = 0;
  var toAppend = [];
  for (var i = 0; i < trainItems.length; i++) {
    var t = trainItems[i];
    var cleanNo = String(t.trainNumber || '').replace(/\D/g, '');
    var k = normTrainKey_(cleanNo);
    if (!k) continue;

    var incomingStops = String(t.trainStops || '').trim();
    var incomingStopsCount = incomingStops ? incomingStops.split(',').length : 0;
    var rowIdx = rowByTrain[k];

    if (rowIdx != null) {
      var row = values[rowIdx];
      var dRow = disp[rowIdx];
      var changed = false;

      if (t.trainName && (isEmptyOrNone_(dRow[cName]) || (!onlyInsertIfMissingOrFillBlank && String(dRow[cName]).trim() !== t.trainName))) {
        row[cName] = t.trainName; changed = true;
      }
      if (!onlyInsertIfMissingOrFillBlank) {
        if (t.fromCode && String(dRow[cFromC]).trim().toUpperCase() !== t.fromCode) { row[cFromC] = t.fromCode; changed = true; }
        if (t.fromName && String(dRow[cFromN]).trim() !== t.fromName) { row[cFromN] = t.fromName; changed = true; }
        var inDep = normHHmm_(t.departure);
        if (inDep && normHHmm_(dRow[cDep]) !== inDep) { row[cDep] = inDep; changed = true; }
        if (t.toCode && String(dRow[cToC]).trim().toUpperCase() !== t.toCode) { row[cToC] = t.toCode; changed = true; }
        if (t.toName && String(dRow[cToN]).trim() !== t.toName) { row[cToN] = t.toName; changed = true; }
        var inArr = normHHmm_(t.arrival);
        if (inArr && normHHmm_(dRow[cArr]) !== inArr) { row[cArr] = inArr; changed = true; }
        if (t.distanceKm > 0 && !(Number(row[cDist]) > 0)) { row[cDist] = Number(t.distanceKm); changed = true; }
        if (t.routePointsCount > 0 && t.routePointsCount !== (Number(row[cPts]) || 0)) { row[cPts] = Number(t.routePointsCount); changed = true; }
      }
      var exStops = String(dRow[cStops] || '').trim();
      var exStopsCount = exStops ? exStops.split(',').length : 0;
      if (incomingStops && (isEmptyOrNone_(exStops) || incomingStopsCount > exStopsCount || (!onlyInsertIfMissingOrFillBlank && incomingStopsCount >= exStopsCount && exStops !== incomingStops))) {
        row[cStops] = incomingStops;
        changed = true;
      }
      if (changed) {
        sheet.getRange(rowIdx + 1, 1, 1, lastCol).setValues([row]);
        updated++;
      }
    } else {
      var newRow = blankRow_(lastCol);
      newRow[cNum] = cleanNo;
      newRow[cName] = t.trainName || '';
      newRow[cFromC] = t.fromCode || '';
      newRow[cFromN] = t.fromName || '';
      newRow[cDep] = normHHmm_(t.departure) || t.departure || '';
      newRow[cToC] = t.toCode || '';
      newRow[cToN] = t.toName || '';
      newRow[cArr] = normHHmm_(t.arrival) || t.arrival || '';
      if (t.distanceKm > 0) newRow[cDist] = Number(t.distanceKm);
      if (t.routePointsCount > 0) newRow[cPts] = Number(t.routePointsCount);
      newRow[cStops] = incomingStops;
      rowByTrain[k] = values.length + toAppend.length;
      toAppend.push(newRow);
      added++;
    }
  }
  if (toAppend.length) {
    sheet.getRange(lastRow + 1, 1, toAppend.length, lastCol).setValues(toAppend);
  }
  return { updated: updated, added: added };
}

function upsertScheduleSheet_(sheet, schedItems) {
  if (!sheet || !schedItems.length) return 0;
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 8);
  var range = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol) : null;
  var values = range ? range.getValues() : [];
  var disp = range ? range.getDisplayValues() : [];

  if (!values.length) {
    var header = [['Schedule_ID', 'Train_Number', 'Train_Name', 'Station_Code', 'Station_Name', 'Arrival', 'Departure', 'Day']];
    sheet.getRange(1, 1, 1, 8).setValues(header);
    values = header;
    disp = header;
    lastRow = 1;
  }

  var hm = headerMap_(disp[0]);
  var cId = hm.scheduleid != null ? hm.scheduleid : 0;
  var cTrainNo = hm.trainnumber != null ? hm.trainnumber : 1;
  var cTrainName = hm.trainname != null ? hm.trainname : 2;
  var cStCode = hm.stationcode != null ? hm.stationcode : 3;
  var cStName = hm.stationname != null ? hm.stationname : 4;
  var cArr = hm.arrival != null ? hm.arrival : 5;
  var cDep = hm.departure != null ? hm.departure : 6;
  var cDay = hm.day != null ? hm.day : 7;

  var rowByKey = {};
  var maxSchedId = 0;
  for (var r = 1; r < disp.length; r++) {
    var k = normTrainKey_(disp[r][cTrainNo]) + '|' + String(disp[r][cStCode] || '').trim().toUpperCase();
    if (disp[r][cTrainNo] && disp[r][cStCode]) rowByKey[k] = r;
    var sid = Number(values[r][cId]);
    if (isFinite(sid) && sid > maxSchedId) maxSchedId = sid;
  }

  var touched = 0;
  var toAppend = [];
  for (var i = 0; i < schedItems.length; i++) {
    var s = schedItems[i];
    var cleanNo = String(s.trainNumber || '').replace(/\D/g, '');
    var stCode = String(s.stationCode || '').trim().toUpperCase();
    if (!cleanNo || !stCode) continue;
    var key = normTrainKey_(cleanNo) + '|' + stCode;
    var rowIdx = rowByKey[key];

    var inArr = normHHmm_(s.arrival);
    var inDep = normHHmm_(s.departure);

    if (rowIdx != null) {
      var row = values[rowIdx];
      var dRow = disp[rowIdx];
      var changed = false;
      if (s.trainName && String(dRow[cTrainName]).trim() !== s.trainName) { row[cTrainName] = s.trainName; changed = true; }
      if (s.stationName && String(dRow[cStName]).trim() !== s.stationName) { row[cStName] = s.stationName; changed = true; }
      if (inArr && normHHmm_(dRow[cArr]) !== inArr) { row[cArr] = inArr; changed = true; }
      if (inDep && normHHmm_(dRow[cDep]) !== inDep) { row[cDep] = inDep; changed = true; }
      if (s.day && Number(row[cDay]) !== Number(s.day)) { row[cDay] = Number(s.day); changed = true; }
      if (changed) {
        sheet.getRange(rowIdx + 1, 1, 1, lastCol).setValues([row]);
        touched++;
      }
    } else {
      maxSchedId++;
      var newRow = blankRow_(lastCol);
      newRow[cId] = maxSchedId;
      newRow[cTrainNo] = cleanNo;
      newRow[cTrainName] = s.trainName || '';
      newRow[cStCode] = stCode;
      newRow[cStName] = s.stationName || stCode;
      newRow[cArr] = inArr || 'None';
      newRow[cDep] = inDep || 'None';
      newRow[cDay] = s.day ? Number(s.day) : 1;
      rowByKey[key] = values.length + toAppend.length;
      toAppend.push(newRow);
      touched++;
    }
  }
  if (toAppend.length) {
    sheet.getRange(lastRow + 1, 1, toAppend.length, lastCol).setValues(toAppend);
  }
  return touched;
}

/* ==================== UTILITIES ==================== */

function normCol_(name) {
  return String(name || '').replace(/^\uFEFF/, '').trim().toLowerCase().replace(/[_\s-]+/g, '');
}

function headerMap_(headers) {
  var m = {};
  for (var i = 0; i < headers.length; i++) {
    var k = normCol_(headers[i]);
    if (k && m[k] == null) m[k] = i;
  }
  return m;
}

function normTrainKey_(raw) {
  var d = String(raw || '').replace(/\D/g, '').replace(/^0+/, '');
  return d || String(raw || '').trim();
}

function isEmptyOrNone_(val) {
  var s = String(val == null ? '' : val).trim();
  return !s || /^none$/i.test(s) || s === '--:--' || s === '-';
}

function normHHmm_(raw) {
  var s = String(raw == null ? '' : raw).trim();
  if (isEmptyOrNone_(s)) return null;
  var m = s.match(/^(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?$/);
  if (!m) return null;
  var h = parseInt(m[1], 10), min = parseInt(m[2], 10);
  var mer = (m[3] || '').toUpperCase();
  if (min < 0 || min > 59) return null;
  if (mer === 'AM' && h === 12) h = 0;
  if (mer === 'PM' && h >= 1 && h <= 11) h += 12;
  if (h < 0 || h > 23) return null;
  return (h < 10 ? '0' + h : '' + h) + ':' + (min < 10 ? '0' + min : '' + min);
}

function parseTimeMins_(hhmm) {
  var norm = normHHmm_(hhmm);
  if (!norm) return null;
  var parts = norm.split(':');
  return parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10);
}

function isScheduledStopTimes_(arrHHmm, depHHmm) {
  if (!arrHHmm && !depHHmm) return false;
  if (arrHHmm && depHHmm && arrHHmm === depHHmm) return false;
  return true;
}

function extractStopCode_(tok) {
  var w = String(tok || '').trim();
  if (!w) return '';
  var m = w.match(/\(\s*([A-Za-z0-9]+)\s*\)/);
  if (m) return m[1].toUpperCase();
  if (/^[A-Za-z0-9]{1,6}$/.test(w)) return w.toUpperCase();
  return '';
}

function haversineKm_(lat1, lon1, lat2, lon2) {
  var toRad = Math.PI / 180;
  var dLat = (lat2 - lat1) * toRad;
  var dLon = (lon2 - lon1) * toRad;
  var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function blankRow_(len) {
  var a = [];
  for (var i = 0; i < len; i++) a.push('');
  return a;
}

function jsonResponse_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
