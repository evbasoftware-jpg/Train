/**
 * Complete Google Apps Script Web App Handler for Train Live Location
 * Spreadsheet ID: 137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA
 * Script ID: 15iF14PctGanUwJJWEtj19nEyTbxMdIIojJGdTW5muXF-Ll7fEZ1jP7m5
 *
 * Script Properties-এ সমর্থিত Key সমূহ:
 *   - RAILRADAR_API_KEYS (একাধিক Key কমা, স্পেস, নতুন লাইন বা JSON Array আকারে)
 *   - RAILRADAR_API_KEY  (একটি Key)
 *   - RAPIDAPI_KEY       (ঐচ্ছিক IRCTC RapidAPI fallback)
 *
 * ডিপ্লয় নিয়ম: Code.gs-এ পুরো কোড পেস্ট করে Save করুন → Deploy → Manage deployments → Edit (✏️) → Version: New version → Deploy।
 */

var DEFAULT_TRAIN_SPREADSHEET_ID = '137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA';
var GPS_CACHE_TTL_SECONDS = 900; // 15 মিনিট
var TRAIN_ENRICH_CACHE_TTL_SECONDS = 21600; // 6 ঘণ্টা cooldown
var DAY_CODES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

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
    if (action === 'testapi' || action === 'diag') {
      var testFrom = String(p.from || 'BWN').trim().toUpperCase();
      var testTo = String(p.to || 'AMP').trim().toUpperCase();
      return jsonResponse_(handleTestRailRadarApi_(testFrom, testTo));
    }

    var rrKeys = getRailRadarApiKeys_();
    var props = PropertiesService.getScriptProperties();
    var hasRapid = !!String(props.getProperty('RAPIDAPI_KEY') || '').trim();
    return jsonResponse_({
      success: true,
      status: 'ok',
      service: 'TrainLiveLocation',
      apiConfigured: rrKeys.length > 0 || hasRapid,
      railRadarKeyCount: rrKeys.length
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

/* ==================== API KEY MANAGEMENT ==================== */

function getRailRadarApiKeys_() {
  var props = PropertiesService.getScriptProperties();
  var rawKeys = [
    props.getProperty('RAILRADAR_API_KEYS'),
    props.getProperty('RAILRADAR_API_KEY'),
    props.getProperty('RAILRADAR_KEY'),
    props.getProperty('RAIL_RADAR_API_KEY')
  ];
  var seen = {};
  var keys = [];
  for (var i = 0; i < rawKeys.length; i++) {
    var raw = String(rawKeys[i] || '').trim();
    if (!raw) continue;
    var parts = [];
    if (raw.charAt(0) === '[') {
      try {
        var parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) parts = parsed;
      } catch (_) {}
    }
    if (!parts.length) {
      parts = raw.split(/[,;\n\r\s|]+/);
    }
    for (var j = 0; j < parts.length; j++) {
      var k = String(parts[j] || '').replace(/^["']|["']$/g, '').trim();
      if (k && !seen[k]) {
        seen[k] = true;
        keys.push(k);
      }
    }
  }
  return keys;
}

var LAST_RAILRADAR_ERROR_ = null;
var FAILED_RR_KEYS_ = {};

function maskApiKey_(key) {
  var s = String(key || '');
  if (s.length <= 8) return '***';
  return s.slice(0, 4) + '...' + s.slice(-4) + ' (len:' + s.length + ')';
}

function parseRailRadarErr_(code, txt) {
  var msg = '';
  var errCode = '';
  try {
    var parsed = JSON.parse(txt || '{}');
    if (parsed && parsed.error) {
      if (typeof parsed.error === 'string') msg = parsed.error;
      else {
        errCode = String(parsed.error.code || '');
        msg = String(parsed.error.message || '');
      }
    } else if (parsed && parsed.message) {
      msg = String(parsed.message);
    }
  } catch (_) {
    msg = String(txt || '').slice(0, 140);
  }
  return {
    httpStatus: code,
    code: errCode || ('HTTP_' + code),
    message: msg || ('HTTP ' + code)
  };
}

function handleTestRailRadarApi_(fromCode, toCode) {
  var keys = getRailRadarApiKeys_();
  var url = 'https://api.railradar.in/v1/trains/between/' + encodeURIComponent(fromCode) + '/' + encodeURIComponent(toCode);
  var results = [];
  for (var i = 0; i < keys.length; i++) {
    var key = keys[i];
    try {
      var resp = UrlFetchApp.fetch(url, {
        headers: {
          'X-Api-Key': key,
          'Accept': 'application/json'
        },
        muteHttpExceptions: true
      });
      var code = resp.getResponseCode();
      var txt = resp.getContentText() || '{}';
      if (code >= 200 && code < 300) {
        var json = JSON.parse(txt);
        var trains = (json && json.data && Array.isArray(json.data.trains)) ? json.data.trains : [];
        results.push({
          keyIndex: i + 1,
          keyMask: maskApiKey_(key),
          ok: true,
          httpStatus: code,
          trainsFound: trains.length
        });
      } else {
        var errObj = parseRailRadarErr_(code, txt);
        results.push({
          keyIndex: i + 1,
          keyMask: maskApiKey_(key),
          ok: false,
          httpStatus: code,
          errorCode: errObj.code,
          errorMessage: errObj.message
        });
      }
    } catch (e) {
      results.push({
        keyIndex: i + 1,
        keyMask: maskApiKey_(key),
        ok: false,
        httpStatus: 0,
        errorMessage: String(e && e.message ? e.message : e)
      });
    }
  }
  return {
    success: true,
    routeTested: fromCode + ' -> ' + toCode,
    railRadarKeyCount: keys.length,
    keys: results
  };
}

function fetchRailRadarJson_(pathAndQuery) {
  var keys = getRailRadarApiKeys_();
  if (!keys.length) return null;
  var url = 'https://api.railradar.in' + pathAndQuery;
  for (var i = 0; i < keys.length; i++) {
    var key = keys[i];
    if (FAILED_RR_KEYS_[key]) continue;
    try {
      var resp = UrlFetchApp.fetch(url, {
        headers: {
          'X-Api-Key': key,
          'Accept': 'application/json'
        },
        muteHttpExceptions: true
      });
      var code = resp.getResponseCode();
      var txt = resp.getContentText() || '{}';
      if (code >= 200 && code < 300) {
        var json = JSON.parse(txt);
        if (json && json.success !== false) {
          LAST_RAILRADAR_ERROR_ = null;
          return json;
        }
      } else {
        LAST_RAILRADAR_ERROR_ = parseRailRadarErr_(code, txt);
        if (code === 401 || code === 403 || code === 429) {
          FAILED_RR_KEYS_[key] = code;
        }
      }
    } catch (e) {
      LAST_RAILRADAR_ERROR_ = { httpStatus: 0, code: 'FETCH_ERROR', message: String(e && e.message ? e.message : e) };
    }
  }
  return null;
}

function fetchRailRadarJsonBatch_(paths) {
  var keys = getRailRadarApiKeys_();
  var activeKeys = [];
  for (var ki = 0; ki < keys.length; ki++) {
    if (!FAILED_RR_KEYS_[keys[ki]]) activeKeys.push(keys[ki]);
  }
  if (!activeKeys.length || !paths.length) return {};
  var out = {};
  var batchSize = 5;
  for (var start = 0; start < paths.length; start += batchSize) {
    activeKeys = [];
    for (var ak = 0; ak < keys.length; ak++) {
      if (!FAILED_RR_KEYS_[keys[ak]]) activeKeys.push(keys[ak]);
    }
    if (!activeKeys.length) break;
    var slice = paths.slice(start, start + batchSize);
    var reqs = [];
    var reqKeys = [];
    for (var i = 0; i < slice.length; i++) {
      var key = activeKeys[(start + i) % activeKeys.length];
      reqKeys.push(key);
      reqs.push({
        url: 'https://api.railradar.in' + slice[i],
        method: 'get',
        headers: {
          'X-Api-Key': key,
          'Accept': 'application/json'
        },
        muteHttpExceptions: true
      });
    }
    try {
      var responses = UrlFetchApp.fetchAll(reqs);
      for (var j = 0; j < responses.length; j++) {
        var code = responses[j].getResponseCode();
        var txt = responses[j].getContentText() || '{}';
        if (code >= 200 && code < 300) {
          try {
            var json = JSON.parse(txt);
            if (json && json.success !== false) {
              out[slice[j]] = json;
            }
          } catch (_) {}
        } else {
          LAST_RAILRADAR_ERROR_ = parseRailRadarErr_(code, txt);
          if (code === 401 || code === 403 || code === 429) {
            FAILED_RR_KEYS_[reqKeys[j]] = code;
          }
        }
      }
    } catch (_) {
      for (var k = 0; k < slice.length; k++) {
        var single = fetchRailRadarJson_(slice[k]);
        if (single) out[slice[k]] = single;
      }
    }
  }
  return out;
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
  var stCoords = readStationMap_(ss.getSheetByName('Station'));
  var trainSheetIndex = readTrainSheetCompleteness_(ss.getSheetByName('Train'));
  var sheetTrains = searchBetweenFromSheets_(ss, stCoords, fromCode, toCode);

  var rrKeys = getRailRadarApiKeys_();
  var props = PropertiesService.getScriptProperties();
  var hasExternalApi = rrKeys.length > 0 || !!String(props.getProperty('RAPIDAPI_KEY') || '').trim();
  var cache = CacheService.getScriptCache();
  var betweenCacheKey = 'between_v3_' + fromCode + '_' + toCode;
  var betweenSyncedRecently = !!cache.get(betweenCacheKey);

  var sheetHasGaps = !sheetTrains.length;
  if (!sheetHasGaps) {
    for (var chk = 0; chk < sheetTrains.length; chk++) {
      var chkKey = normTrainKey_(sheetTrains[chk].train.number);
      var chkMeta = trainSheetIndex[chkKey];
      if (!sheetTrains[chk].runDays || !chkMeta || chkMeta.stopsCount <= 2 || chkMeta.routePointsCount <= 2) {
        sheetHasGaps = true;
        break;
      }
    }
  }

  // শিট ফাঁকা থাকলে, অথবা এখনো এই রুটের RailRadar Sync না হয়ে থাকলে/অসম্পূর্ণ থাকলে লাইভ API থেকে সব ট্রেন + runningDays নিয়ে আসি
  var apiTrains = [];
  if (hasExternalApi && (!sheetTrains.length || !betweenSyncedRecently || sheetHasGaps)) {
    apiTrains = fetchBetweenFromExternalApi_(fromCode, toCode, stCoords);
    if (apiTrains.length) {
      cache.put(betweenCacheKey, '1', TRAIN_ENRICH_CACHE_TTL_SECONDS);
    }
  }

  if (apiTrains.length) {
    try {
      var trainNumsToEnrich = [];
      var seedMetaByKey = {};
      for (var i = 0; i < apiTrains.length; i++) {
        var tr = apiTrains[i];
        var tNo = tr.train.number;
        var tk = normTrainKey_(tNo);
        seedMetaByKey[tk] = tr._meta || {};
        var exMeta = trainSheetIndex[tk];
        if (!exMeta || exMeta.stopsCount <= 2 || exMeta.routePointsCount <= 2 || !exMeta.runningDays) {
          if (trainNumsToEnrich.length < 5 && !cache.get('enriched_' + tk)) {
            trainNumsToEnrich.push(tNo);
          }
        }
      }

      var enrichedByKey = enrichTrainsAndSyncToSheets_(ss, trainNumsToEnrich, seedMetaByKey, stCoords);

      // যে ট্রেনগুলোর ফুল রুট প্যারালাল লিমিটের বাইরে ছিল, তাদের মেটাডেটা + runningDays সেভ করি
      var metaOnlyItems = [];
      for (var j = 0; j < apiTrains.length; j++) {
        var tr2 = apiTrains[j];
        var tk3 = normTrainKey_(tr2.train.number);
        var enr = enrichedByKey[tk3];
        if (enr) {
          if (enr.runningDays && !tr2.runDays) tr2.runDays = enr.runningDays;
          if (enr.sourceCode) tr2.train.sourceCode = enr.sourceCode;
          if (enr.sourceName) tr2.train.sourceName = enr.sourceName;
          if (enr.destCode) tr2.train.destCode = enr.destCode;
          if (enr.destName) tr2.train.destName = enr.destName;
          if (enr.trainType) tr2.train.type = enr.trainType;
          if (enr.zone) tr2.train.zone = enr.zone;
        } else {
          var m = tr2._meta || {};
          metaOnlyItems.push({
            trainNumber: tr2.train.number,
            trainName: tr2.train.name,
            trainType: m.trainType || '',
            zone: m.zone || '',
            fromCode: m.sourceCode || '',
            fromName: m.sourceName || '',
            departure: m.originDeparture || '',
            toCode: m.destCode || '',
            toName: m.destName || '',
            arrival: m.destArrival || '',
            durationHours: m.durationHours != null ? m.durationHours : '',
            durationMinutes: m.durationMinutes != null ? m.durationMinutes : '',
            distanceKm: m.totalDistanceKm || 0,
            returnTrain: m.returnTrain || '',
            firstAc: m.firstAc,
            secondAc: m.secondAc,
            thirdAc: m.thirdAc,
            chairCar: m.chairCar,
            firstClass: m.firstClass,
            sleeper: m.sleeper,
            runningDays: tr2.runDays || ''
          });
        }
        delete tr2._meta;
      }
      if (metaOnlyItems.length) {
        upsertTrainSheet_(ss.getSheetByName('Train'), metaOnlyItems, true);
      }
    } catch (_) {}

    // API + Sheet রেজাল্ট মার্জ করি (API-কে অগ্রাধিকার দিয়ে, এবং শিটে থাকা অতিরিক্ত ভ্যালিড ট্রেনও রেখে)
    var mergedByKey = {};
    var mergedList = [];
    for (var ai = 0; ai < apiTrains.length; ai++) {
      var ak = normTrainKey_(apiTrains[ai].train.number);
      if (!mergedByKey[ak]) {
        mergedByKey[ak] = apiTrains[ai];
        mergedList.push(apiTrains[ai]);
      }
    }
    for (var si2 = 0; si2 < sheetTrains.length; si2++) {
      var sk = normTrainKey_(sheetTrains[si2].train.number);
      if (!mergedByKey[sk]) {
        mergedByKey[sk] = sheetTrains[si2];
        mergedList.push(sheetTrains[si2]);
      }
    }
    mergedList.sort(function (a, b) {
      var ma = parseTimeMins_(a.from.departure);
      var mb = parseTimeMins_(b.from.departure);
      if (ma == null) ma = 9999;
      if (mb == null) mb = 9999;
      return ma !== mb ? ma - mb : String(a.train.number).localeCompare(String(b.train.number));
    });
    return { success: true, source: 'api', data: { trains: mergedList } };
  }

  if (sheetTrains.length) {
    var needEnrich = [];
    for (var si = 0; si < sheetTrains.length && needEnrich.length < 6; si++) {
      var sItem = sheetTrains[si];
      var tKey = normTrainKey_(sItem.train.number);
      var meta = trainSheetIndex[tKey];
      var isIncomplete = !meta || meta.stopsCount <= 2 || meta.routePointsCount <= 2 || !meta.runningDays;
      if (isIncomplete && !cache.get('enriched_' + tKey)) {
        needEnrich.push(sItem.train.number);
      }
    }
    if (needEnrich.length && rrKeys.length) {
      var enrichedMap = enrichTrainsAndSyncToSheets_(ss, needEnrich, {}, stCoords);
      for (var sj = 0; sj < sheetTrains.length; sj++) {
        var tk2 = normTrainKey_(sheetTrains[sj].train.number);
        var eInfo = enrichedMap[tk2];
        if (eInfo) {
          if (eInfo.runningDays) sheetTrains[sj].runDays = eInfo.runningDays;
          if (eInfo.sourceCode) sheetTrains[sj].train.sourceCode = eInfo.sourceCode;
          if (eInfo.sourceName) sheetTrains[sj].train.sourceName = eInfo.sourceName;
          if (eInfo.destCode) sheetTrains[sj].train.destCode = eInfo.destCode;
          if (eInfo.destName) sheetTrains[sj].train.destName = eInfo.destName;
          if (eInfo.trainType) sheetTrains[sj].train.type = eInfo.trainType;
          if (eInfo.zone) sheetTrains[sj].train.zone = eInfo.zone;
        }
      }
    }
    return {
      success: true,
      source: 'sheet',
      apiError: LAST_RAILRADAR_ERROR_ ? (LAST_RAILRADAR_ERROR_.code + ': ' + LAST_RAILRADAR_ERROR_.message) : undefined,
      data: { trains: sheetTrains }
    };
  }

  return {
    success: true,
    apiError: LAST_RAILRADAR_ERROR_ ? (LAST_RAILRADAR_ERROR_.code + ': ' + LAST_RAILRADAR_ERROR_.message) : undefined,
    data: { trains: [] }
  };
}

function handleGetSchedule_(sheetId, trainNo) {
  if (!trainNo) return { success: false, error: 'Missing trainNo' };
  var ss = SpreadsheetApp.openById(sheetId);
  var stMap = readStationMap_(ss.getSheetByName('Station'));
  var info = loadTrainAndRouteFromSheets_(ss, stMap, trainNo);
  if (info.route.length >= 2 && !info.needsEnrich) {
    return { success: true, source: 'sheet', trainName: info.trainName, route: info.route };
  }
  var apiLive = fetchLiveAndRouteFromExternalApi_(trainNo, stMap);
  if (apiLive && apiLive.route && apiLive.route.length >= 2) {
    syncExternalTrainToSheets_(ss, trainNo, apiLive.trainName || info.trainName, apiLive.route, apiLive.meta, true);
    return { success: true, source: 'api', trainName: apiLive.trainName || info.trainName, route: apiLive.route };
  }
  return { success: true, source: 'sheet', trainName: info.trainName, route: info.route };
}

function handleGetLive_(sheetId, trainNo) {
  if (!trainNo) return { success: false, error: 'Missing trainNo' };
  var ss = SpreadsheetApp.openById(sheetId);
  var stMap = readStationMap_(ss.getSheetByName('Station'));
  var info = loadTrainAndRouteFromSheets_(ss, stMap, trainNo);
  var gpsLoc = getFreshGps_(trainNo);

  var apiLive = null;
  if (!gpsLoc || info.route.length < 2 || info.needsEnrich) {
    apiLive = fetchLiveAndRouteFromExternalApi_(trainNo, stMap);
    if (apiLive && apiLive.route && apiLive.route.length >= 2 &&
        (apiLive.route.length >= info.route.length || info.route.length < 2 || info.needsEnrich)) {
      info.route = apiLive.route;
      if (apiLive.trainName) info.trainName = apiLive.trainName;
      if (apiLive.meta && apiLive.meta.runningDays) {
        info.meta = info.meta || {};
        info.meta.runningDays = apiLive.meta.runningDays;
      }
      syncExternalTrainToSheets_(ss, trainNo, info.trainName, info.route, apiLive.meta || info.meta, true);
    }
  }

  var currentLocation = null;
  var source = null;

  if (gpsLoc) {
    currentLocation = buildLocationFromGps_(gpsLoc, info.route);
    source = 'gps';
  } else if (apiLive && apiLive.currentLocation) {
    currentLocation = apiLive.currentLocation;
    source = apiLive.isEstimated ? 'sheet' : 'api';
  } else if (info.route.length) {
    currentLocation = estimateLocationFromRoute_(info.route, info.meta || (apiLive && apiLive.meta));
    source = 'sheet';
  }

  return {
    success: true,
    apiError: (source !== 'api' && source !== 'gps' && LAST_RAILRADAR_ERROR_)
      ? (LAST_RAILRADAR_ERROR_.code + ': ' + LAST_RAILRADAR_ERROR_.message)
      : undefined,
    data: {
      trainName: info.trainName || (apiLive && apiLive.trainName) || '',
      source: source,
      currentLocation: currentLocation,
      route: info.route
    }
  };
}

/* ==================== PARALLEL ENRICHMENT FOR BETWEEN ==================== */

function readTrainSheetCompleteness_(sheet) {
  var map = {};
  if (!sheet || sheet.getLastRow() < 2) return map;
  var disp = sheet.getDataRange().getDisplayValues();
  var thm = headerMap_(disp[0]);
  var tcNum = thm.trainnumber != null ? thm.trainnumber : 0;
  var tcPts = thm.routepointscount != null ? thm.routepointscount : 20;
  var tcStops = thm.trainstops != null ? thm.trainstops : 21;
  var tcDays = thm.runningdays != null ? thm.runningdays : (thm.rundays != null ? thm.rundays : -1);
  for (var r = 1; r < disp.length; r++) {
    var k = normTrainKey_(disp[r][tcNum]);
    if (!k) continue;
    var stopsStr = String(disp[r][tcStops] || '').trim();
    var pts = parseInt(disp[r][tcPts], 10) || 0;
    var rDays = tcDays >= 0 ? String(disp[r][tcDays] || '').trim() : '';
    map[k] = {
      stopsCount: stopsStr ? stopsStr.split(',').length : 0,
      routePointsCount: pts,
      runningDays: rDays
    };
  }
  return map;
}

function enrichTrainsAndSyncToSheets_(ss, trainNumbers, seedMetaByKey, stCoords) {
  var out = {};
  if (!trainNumbers || !trainNumbers.length) return out;
  var cache = CacheService.getScriptCache();
  var pathToTrain = {};
  var paths = [];
  for (var i = 0; i < trainNumbers.length; i++) {
    var tNo = String(trainNumbers[i] || '').replace(/\D/g, '');
    if (!tNo) continue;
    var path = '/v1/trains/' + encodeURIComponent(tNo);
    pathToTrain[path] = tNo;
    paths.push(path);
  }
  if (!paths.length) return out;

  var jsonMap = fetchRailRadarJsonBatch_(paths);
  var allStations = [];
  var seenSt = {};
  var allTrainItems = [];
  var allSched = [];

  for (var p in pathToTrain) {
    var num = pathToTrain[p];
    var tk = normTrainKey_(num);
    var json = jsonMap[p];
    if (!json) continue;
    var d = json.data || json;
    if (!d) continue;

    var trMeta = extractTrainMeta_(d, (seedMetaByKey || {})[tk]);
    var route = normalizeExternalRoute_(Array.isArray(d.route) ? d.route : [], stCoords);
    if (route.length < 2) continue;
    cache.put('enriched_' + tk, '1', TRAIN_ENRICH_CACHE_TTL_SECONDS);

    var halts = [];
    var day = 1, prevMin = null;
    for (var ri = 0; ri < route.length; ri++) {
      var st = route[ri];
      if (st.isHalt !== false && st.stationCode) halts.push(st.stationCode);
      if (st.stationCode && !seenSt[st.stationCode]) {
        seenSt[st.stationCode] = true;
        var stObj = { stationCode: st.stationCode, stationName: st.stationName || st.stationCode };
        if (st.lat != null && st.lon != null) { stObj.latitude = st.lat; stObj.longitude = st.lon; }
        allStations.push(stObj);
      }
      var minNow = parseTimeMins_(st.arrival || st.scheduledTime || st.departure);
      if (prevMin != null && minNow != null && minNow + 120 < prevMin) day++;
      if (minNow != null) prevMin = minNow;

      var arrVal = (st.arrival && st.arrival !== '--:--') ? st.arrival : 'None';
      var depVal = (st.departure && st.departure !== '--:--') ? st.departure : 'None';
      if (ri === 0) arrVal = 'None';
      if (ri === route.length - 1) depVal = 'None';
      if (st.isHalt === false) {
        var passT = st.scheduledTime && st.scheduledTime !== '--:--' ? st.scheduledTime : (arrVal !== 'None' ? arrVal : depVal);
        if (passT && passT !== 'None') {
          arrVal = passT;
          depVal = passT;
        }
      }
      allSched.push({
        sequence: ri + 1,
        trainNumber: num,
        trainName: trMeta.trainName || '',
        stationCode: st.stationCode,
        stationName: st.stationName || st.stationCode,
        arrival: arrVal,
        departure: depVal,
        day: st.day || day,
        isHalt: st.isHalt !== false ? 'Y' : 'N'
      });
    }

    var firstHalt = route[0];
    var lastHalt = route[route.length - 1];
    for (var fi = 0; fi < route.length; fi++) {
      if (route[fi].isHalt !== false) { firstHalt = route[fi]; break; }
    }
    for (var li = route.length - 1; li >= 0; li--) {
      if (route[li].isHalt !== false) { lastHalt = route[li]; break; }
    }

    var depOrigin = normHHmm_(firstHalt.departure || firstHalt.scheduledTime) || trMeta.originDeparture || '';
    var arrDest = normHHmm_(lastHalt.arrival || lastHalt.scheduledTime) || trMeta.destArrival || '';
    var durObj = computeDurationHrMin_(depOrigin, arrDest, lastHalt.day || day, trMeta.durationMinutesTotal);
    var distTotal = trMeta.totalDistanceKm || computeRouteDistKm_(route, stCoords);

    allTrainItems.push({
      trainNumber: num,
      trainName: trMeta.trainName || '',
      trainType: trMeta.trainType || '',
      zone: trMeta.zone || '',
      fromCode: firstHalt.stationCode || trMeta.sourceCode || '',
      fromName: firstHalt.stationName || trMeta.sourceName || '',
      departure: depOrigin,
      toCode: lastHalt.stationCode || trMeta.destCode || '',
      toName: lastHalt.stationName || trMeta.destName || '',
      arrival: arrDest,
      durationHours: durObj.hours,
      durationMinutes: durObj.minutes,
      distanceKm: distTotal,
      returnTrain: trMeta.returnTrain || '',
      firstAc: trMeta.firstAc,
      secondAc: trMeta.secondAc,
      thirdAc: trMeta.thirdAc,
      chairCar: trMeta.chairCar,
      firstClass: trMeta.firstClass,
      sleeper: trMeta.sleeper,
      routePointsCount: route.length,
      trainStops: halts.join(', '),
      runningDays: trMeta.runningDays || ''
    });

    out[tk] = {
      trainName: trMeta.trainName || '',
      trainType: trMeta.trainType || '',
      zone: trMeta.zone || '',
      sourceCode: firstHalt.stationCode || trMeta.sourceCode || '',
      sourceName: firstHalt.stationName || trMeta.sourceName || '',
      destCode: lastHalt.stationCode || trMeta.destCode || '',
      destName: lastHalt.stationName || trMeta.destName || '',
      runningDays: trMeta.runningDays || ''
    };
  }

  if (allStations.length) upsertStationsSheet_(ss.getSheetByName('Station'), allStations);
  if (allTrainItems.length) upsertTrainSheet_(ss.getSheetByName('Train'), allTrainItems, false);
  if (allSched.length) upsertScheduleSheet_(ss.getSheetByName('Schedule'), allSched, true);
  return out;
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

function searchBetweenFromSheets_(ss, stCoords, fromCode, toCode) {
  var schedSheet = ss.getSheetByName('Schedule');
  var trainSheet = ss.getSheetByName('Train');

  var fGeo = stCoords[fromCode];
  var tGeo = stCoords[toCode];
  var approxDist = (fGeo && tGeo && fGeo.lat != null && tGeo.lat != null)
    ? Math.round(haversineKm_(fGeo.lat, fGeo.lon, tGeo.lat, tGeo.lon) * 1.2 * 10) / 10
    : 0;

  var byTrain = {};
  var trainMetaByKey = {};

  // 1) প্রথমে Train শিট পড়ি যাতে প্রতিটি ট্রেনের Train_Stops, Running_Days ও সঠিক সোর্স/ডেস্ট মেলে
  if (trainSheet && trainSheet.getLastRow() >= 2) {
    var tDisp = trainSheet.getDataRange().getDisplayValues();
    var thm = headerMap_(tDisp[0]);
    var tcNum = thm.trainnumber != null ? thm.trainnumber : 0;
    var tcName = thm.trainname != null ? thm.trainname : 1;
    var tcType = thm.traintype != null ? thm.traintype : 2;
    var tcZone = thm.zone != null ? thm.zone : 3;
    var tcFrom = thm.fromstationcode != null ? thm.fromstationcode : 4;
    var tcDep = thm.departure != null ? thm.departure : 6;
    var tcTo = thm.tostationcode != null ? thm.tostationcode : 7;
    var tcArr = thm.arrival != null ? thm.arrival : 9;
    var tcDist = thm.distancekm != null ? thm.distancekm : 12;
    var tcStops = thm.trainstops != null ? thm.trainstops : 21;
    var tcDays = thm.runningdays != null ? thm.runningdays : (thm.rundays != null ? thm.rundays : -1);

    for (var r2 = 1; r2 < tDisp.length; r2++) {
      var row = tDisp[r2];
      var num = String(row[tcNum] || '').replace(/\D/g, '');
      var tk2 = normTrainKey_(num);
      if (!tk2) continue;
      var tName = String(row[tcName] || '').trim();
      var rDays = tcDays >= 0 ? normalizeRunDays_(row[tcDays]) : '';
      var tType = String(row[tcType] || '').trim();
      var tZone = String(row[tcZone] || '').trim();
      var fc = String(row[tcFrom] || '').trim().toUpperCase();
      var tc = String(row[tcTo] || '').trim().toUpperCase();

      var stopsRaw = String(row[tcStops] || '').trim();
      var tokens = stopsRaw ? stopsRaw.split(/[,;|\n\r]+|->|→/) : [];
      var codes = [];
      var stopSet = {};
      for (var i = 0; i < tokens.length; i++) {
        var cTok = extractStopCode_(tokens[i]);
        if (cTok) {
          codes.push(cTok);
          stopSet[cTok] = true;
        }
      }
      if (!codes.length && fc && tc) codes = [fc, tc];
      else {
        if (fc && (!codes.length || codes[0] !== fc)) codes.unshift(fc);
        if (tc && (!codes.length || codes[codes.length - 1] !== tc)) codes.push(tc);
      }

      trainMetaByKey[tk2] = {
        number: num,
        name: tName,
        type: tType,
        zone: tZone,
        sourceCode: fc,
        destCode: tc,
        runDays: rDays,
        stopSet: codes.length >= 3 ? stopSet : null
      };

      var fIdx = codes.indexOf(fromCode);
      var tIdx = fIdx >= 0 ? codes.indexOf(toCode, fIdx + 1) : -1;
      if (fIdx >= 0 && tIdx > fIdx) {
        var dep2 = (fIdx === 0 ? normHHmm_(row[tcDep]) : null) || '--:--';
        var arr2 = (tIdx === codes.length - 1 ? normHHmm_(row[tcArr]) : null) || '--:--';
        var dist2 = (fIdx === 0 && tIdx === codes.length - 1) ? (Number(row[tcDist]) || approxDist) : approxDist;
        byTrain[tk2] = {
          train: { number: num, name: tName, type: tType, zone: tZone, sourceCode: fc, destCode: tc },
          from: { departure: dep2, stationCode: fromCode },
          to: { arrival: arr2, stationCode: toCode },
          distance: dist2,
          runDays: rDays
        };
      }
    }
  }

  // 2) Schedule শিট (Arr == Dep হওয়া আসল ট্রেন যেমন MEMU/প্যাসেঞ্জার বা টার্মিনাল স্টপ বাদ দেব না — শুধু Is_Halt=N বা নন-হল্ট Train_Stops বাদ দেব)
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
    var cHalt = shm.ishalt != null ? shm.ishalt : -1;

    var grouped = {};
    for (var r = 1; r < sDisp.length; r++) {
      var stC = String(sDisp[r][cSt] || '').trim().toUpperCase();
      if (stC !== fromCode && stC !== toCode) continue;
      var tNoRaw = String(sDisp[r][cNo] || '').replace(/\D/g, '');
      var tk = normTrainKey_(tNoRaw);
      if (!tk) continue;
      var arrStr = normHHmm_(sDisp[r][cArr]);
      var depStr = normHHmm_(sDisp[r][cDep]);
      var explicitHalt = parseHaltCell_(cHalt >= 0 ? sDisp[r][cHalt] : '');
      var metaSet = trainMetaByKey[tk] ? trainMetaByKey[tk].stopSet : null;
      if (!isBetweenScheduleStop_(arrStr, depStr, explicitHalt, stC, metaSet)) continue;

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

    for (var k in grouped) {
      var g = grouped[k];
      if (!g.from || !g.to) continue;
      var fm = (g.from.day - 1) * 1440 + (parseTimeMins_(g.from.dep || g.from.arr) || 0);
      var tm = (g.to.day - 1) * 1440 + (parseTimeMins_(g.to.arr || g.to.dep) || 0);
      if (tm <= fm && !(g.to.id > g.from.id && g.to.day === g.from.day && fm === tm)) continue;
      var meta2 = trainMetaByKey[k] || {};
      var exItem = byTrain[k];
      var depOut = g.from.dep || g.from.arr || (exItem ? exItem.from.departure : '--:--') || '--:--';
      var arrOut = g.to.arr || g.to.dep || (exItem ? exItem.to.arrival : '--:--') || '--:--';
      byTrain[k] = {
        train: {
          number: g.num,
          name: g.name || meta2.name || '',
          type: meta2.type || '',
          zone: meta2.zone || '',
          sourceCode: meta2.sourceCode || '',
          destCode: meta2.destCode || ''
        },
        from: { departure: depOut, stationCode: fromCode },
        to: { arrival: arrOut, stationCode: toCode },
        distance: (exItem && exItem.distance) || approxDist,
        runDays: meta2.runDays || (exItem ? exItem.runDays : '') || ''
      };
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

function loadTrainAndRouteFromSheets_(ss, stMap, trainNo) {
  var targetKey = normTrainKey_(trainNo);
  var trainName = '';
  var schedRows = [];
  var trainMeta = null;
  var trainStopsSet = {};
  var trainStopsCount = 0;

  var trainSheet = ss.getSheetByName('Train');
  if (trainSheet && trainSheet.getLastRow() >= 2) {
    var tDisp = trainSheet.getDataRange().getDisplayValues();
    var thm = headerMap_(tDisp[0]);
    var tcNum = thm.trainnumber != null ? thm.trainnumber : 0;
    var tcName = thm.trainname != null ? thm.trainname : 1;
    var tcFrom = thm.fromstationcode != null ? thm.fromstationcode : 4;
    var tcDep = thm.departure != null ? thm.departure : 6;
    var tcTo = thm.tostationcode != null ? thm.tostationcode : 7;
    var tcArr = thm.arrival != null ? thm.arrival : 9;
    var tcPts = thm.routepointscount != null ? thm.routepointscount : 20;
    var tcStops = thm.trainstops != null ? thm.trainstops : 21;
    var tcDays = thm.runningdays != null ? thm.runningdays : (thm.rundays != null ? thm.rundays : -1);

    for (var r = 1; r < tDisp.length; r++) {
      if (normTrainKey_(tDisp[r][tcNum]) === targetKey) {
        trainName = String(tDisp[r][tcName] || '').trim();
        var sRaw = String(tDisp[r][tcStops] || '').trim();
        if (sRaw) {
          var toks = sRaw.split(/[,;|\n\r]+|->|→/);
          for (var ti = 0; ti < toks.length; ti++) {
            var sc = extractStopCode_(toks[ti]);
            if (sc) {
              trainStopsSet[sc] = true;
              trainStopsCount++;
            }
          }
        }
        trainMeta = {
          trainName: trainName,
          sourceCode: String(tDisp[r][tcFrom] || '').trim().toUpperCase(),
          originDeparture: normHHmm_(tDisp[r][tcDep]) || '',
          destCode: String(tDisp[r][tcTo] || '').trim().toUpperCase(),
          destArrival: normHHmm_(tDisp[r][tcArr]) || '',
          routePointsCount: parseInt(tDisp[r][tcPts], 10) || 0,
          trainStops: sRaw,
          runningDays: tcDays >= 0 ? normalizeRunDays_(tDisp[r][tcDays]) : ''
        };
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
    var cHalt = shm.ishalt != null ? shm.ishalt : -1;

    for (var r2 = 1; r2 < sDisp.length; r2++) {
      if (normTrainKey_(sDisp[r2][cNo]) !== targetKey) continue;
      if (!trainName && sDisp[r2][cName]) trainName = String(sDisp[r2][cName]).trim();
      var code = String(sDisp[r2][cSt] || '').trim().toUpperCase();
      var arr = normHHmm_(sDisp[r2][cArr]);
      var dep = normHHmm_(sDisp[r2][cDep]);
      var expHalt = parseHaltCell_(cHalt >= 0 ? sDisp[r2][cHalt] : '');
      schedRows.push({
        id: Number(sDisp[r2][cId]) || r2,
        code: code,
        name: String(sDisp[r2][cStName] || '').trim(),
        arr: arr,
        dep: dep,
        day: parseInt(sDisp[r2][cDay], 10) || 1,
        order: r2,
        explicitHalt: expHalt
      });
    }
  }

  // সোর্স স্টেশনে কখনো নষ্ট Arrival (যেমন ১৬:৪৬ > ০৬:৫০+৬০) থাকলে Departure দিয়ে সর্ট করি
  var rowSortMin = function (row) {
    var am = parseTimeMins_(row.arr);
    var dm = parseTimeMins_(row.dep);
    if (am != null && dm != null && row.day <= 1 && am > dm + 60) return dm;
    return am != null ? am : (dm != null ? dm : 99999);
  };
  schedRows.sort(function (a, b) {
    if (a.day !== b.day) return a.day - b.day;
    var ma = rowSortMin(a);
    var mb = rowSortMin(b);
    if (ma !== mb) return ma - mb;
    return a.id !== b.id ? a.id - b.id : a.order - b.order;
  });

  var hasDiffArrDep = false;
  var hasCorruptedTimes = false;
  for (var hi = 0; hi < schedRows.length; hi++) {
    var rChk = schedRows[hi];
    if (rChk.arr && rChk.dep && rChk.arr !== rChk.dep) {
      hasDiffArrDep = true;
      var amChk = parseTimeMins_(rChk.arr);
      var dmChk = parseTimeMins_(rChk.dep);
      if (hi === 0 && amChk != null && dmChk != null && amChk > dmChk + 60) {
        hasCorruptedTimes = true;
      }
    }
  }

  // টার্মিনাল স্টেশন (প্রথম ও শেষ) সবসময় থামবে; মাঝের স্টেশন কেবল Is_Halt=N / Train_Stops বাদ পড়লে / ভিন্ন Arr-Dep রুটে Arr==Dep হলে নন-হল্ট হবে
  var route = [];
  for (var j = 0; j < schedRows.length; j++) {
    var sr = schedRows[j];
    var stGeo = stMap[sr.code] || {};
    var sched = sr.arr || sr.dep || '--:--';
    var isTerminal = (j === 0 || j === schedRows.length - 1);
    var isHaltVal = true;
    if (!isTerminal) {
      if (sr.explicitHalt !== null) {
        isHaltVal = sr.explicitHalt;
      } else if (trainStopsCount >= 3) {
        isHaltVal = !!trainStopsSet[sr.code];
      } else if (sr.arr && sr.dep && sr.arr === sr.dep && hasDiffArrDep) {
        isHaltVal = false;
      } else if (!sr.arr && !sr.dep) {
        isHaltVal = false;
      }
    }
    route.push({
      stationCode: sr.code,
      stationName: sr.name || stGeo.name || sr.code,
      sequence: j + 1,
      lat: stGeo.lat != null ? stGeo.lat : null,
      lon: stGeo.lon != null ? stGeo.lon : null,
      scheduledTime: sched,
      arrival: (j === 0 && sr.dep) ? sr.dep : (sr.arr || sched),
      departure: sr.dep || sched,
      day: sr.day,
      isHalt: isHaltVal
    });
  }

  var needsEnrich = route.length < 2 ||
    !trainMeta ||
    !trainMeta.runningDays ||
    !trainMeta.destCode ||
    hasCorruptedTimes;

  return { trainName: trainName, route: route, meta: trainMeta, needsEnrich: needsEnrich };
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

/* ==================== EXTERNAL RAILWAY API (RAILRADAR v1 + RAPIDAPI) ==================== */

function extractTrainMeta_(obj, seed) {
  var s = seed || {};
  var tr = (obj && obj.train && typeof obj.train === 'object') ? obj.train : (obj || {});
  var name = String(tr.trainName || tr.name || tr.train_name || (obj && obj.trainName) || s.trainName || '').trim();
  var tType = String(tr.type || tr.trainType || tr.train_type || tr.category || (obj && obj.type) || s.trainType || '').trim();
  var zone = String(tr.zone || tr.railwayZone || (obj && obj.zone) || s.zone || '').trim();

  var srcObj = tr.source || tr.origin || (obj && obj.source) || {};
  var dstObj = tr.destination || tr.dest || (obj && obj.destination) || {};
  var sourceCode = String(tr.sourceCode || tr.sourceStationCode || tr.fromCode || (typeof srcObj === 'object' ? (srcObj.code || srcObj.stationCode) : srcObj) || s.sourceCode || '').trim().toUpperCase();
  var sourceName = String(tr.sourceName || tr.sourceStationName || tr.fromName || (typeof srcObj === 'object' ? (srcObj.name || srcObj.stationName) : '') || s.sourceName || '').trim();
  var destCode = String(tr.destinationCode || tr.destCode || tr.destinationStationCode || tr.toCode || (typeof dstObj === 'object' ? (dstObj.code || dstObj.stationCode) : dstObj) || s.destCode || '').trim().toUpperCase();
  var destName = String(tr.destinationName || tr.destName || tr.destinationStationName || tr.toName || (typeof dstObj === 'object' ? (dstObj.name || dstObj.stationName) : '') || s.destName || '').trim();

  var originDep = normHHmm_(tr.departure || tr.departureTime || (typeof srcObj === 'object' ? srcObj.departure : '')) || s.originDeparture || '';
  var destArr = normHHmm_(tr.arrival || tr.arrivalTime || (typeof dstObj === 'object' ? dstObj.arrival : '')) || s.destArrival || '';
  var totalDist = Number(tr.distanceKm || tr.distance || (obj && obj.distanceKm) || (obj && obj.distance) || s.totalDistanceKm) || 0;
  var durTotalMins = Number(tr.durationMinutes || tr.travelTimeMinutes || tr.duration || (obj && (obj.durationMinutes || obj.travelTimeMinutes)) || s.durationMinutesTotal) || 0;
  var returnTrain = String(tr.returnTrain || tr.returnTrainNumber || (obj && obj.returnTrain) || s.returnTrain || '').replace(/\D/g, '');
  var runDaysRaw = tr.runningDays || tr.runDays || tr.daysOfWeek || tr.days || (obj && (obj.runningDays || obj.runDays || obj.days)) || s.runningDays || '';
  var bitmap = tr.runningDaysBitmap != null ? tr.runningDaysBitmap : (obj && obj.runningDaysBitmap != null ? obj.runningDaysBitmap : s.runningDaysBitmap);
  var runningDays = normalizeRunDays_(runDaysRaw, bitmap);

  var classesObj = tr.classes || tr.availableClasses || tr.coaches || (obj && (obj.classes || obj.availableClasses)) || s.classesRaw || null;
  var clsFlags = parseCoachClassFlags_(classesObj);

  return {
    trainName: name,
    trainType: tType,
    zone: zone,
    sourceCode: sourceCode,
    sourceName: sourceName,
    originDeparture: originDep,
    destCode: destCode,
    destName: destName,
    destArrival: destArr,
    totalDistanceKm: totalDist,
    durationMinutesTotal: durTotalMins,
    durationHours: durTotalMins > 0 ? Math.floor(durTotalMins / 60) : '',
    durationMinutes: durTotalMins > 0 ? (durTotalMins % 60) : '',
    returnTrain: returnTrain,
    runningDays: runningDays,
    firstAc: clsFlags.firstAc,
    secondAc: clsFlags.secondAc,
    thirdAc: clsFlags.thirdAc,
    chairCar: clsFlags.chairCar,
    firstClass: clsFlags.firstClass,
    sleeper: clsFlags.sleeper
  };
}

function parseCoachClassFlags_(raw) {
  var flags = { firstAc: '', secondAc: '', thirdAc: '', chairCar: '', firstClass: '', sleeper: '' };
  if (!raw) return flags;
  var list = [];
  if (Array.isArray(raw)) {
    for (var i = 0; i < raw.length; i++) {
      var item = raw[i];
      list.push(String(typeof item === 'object' ? (item.code || item.name || '') : item).toUpperCase());
    }
  } else if (typeof raw === 'object') {
    for (var k in raw) {
      if (raw[k]) list.push(String(k).toUpperCase());
    }
  } else {
    list = String(raw).toUpperCase().split(/[,;\s]+/);
  }
  if (!list.length) return flags;
  var joined = '|' + list.join('|') + '|';
  flags.firstAc = (joined.indexOf('|1A|') >= 0 || joined.indexOf('|FIRST_AC|') >= 0) ? 1 : 0;
  flags.secondAc = (joined.indexOf('|2A|') >= 0 || joined.indexOf('|SECOND_AC|') >= 0) ? 1 : 0;
  flags.thirdAc = (joined.indexOf('|3A|') >= 0 || joined.indexOf('|THIRD_AC|') >= 0 || joined.indexOf('|3E|') >= 0) ? 1 : 0;
  flags.chairCar = (joined.indexOf('|CC|') >= 0 || joined.indexOf('|EC|') >= 0 || joined.indexOf('|CHAIR_CAR|') >= 0) ? 1 : 0;
  flags.firstClass = (joined.indexOf('|FC|') >= 0 || joined.indexOf('|FIRST_CLASS|') >= 0) ? 1 : 0;
  flags.sleeper = (joined.indexOf('|SL|') >= 0 || joined.indexOf('|SLEEPER|') >= 0) ? 1 : 0;
  return flags;
}

function computeDurationHrMin_(depHHmm, arrHHmm, dayCount, explicitTotalMins) {
  if (explicitTotalMins > 0) {
    return { hours: Math.floor(explicitTotalMins / 60), minutes: explicitTotalMins % 60 };
  }
  var dMin = parseTimeMins_(depHHmm);
  var aMin = parseTimeMins_(arrHHmm);
  if (dMin == null || aMin == null) return { hours: '', minutes: '' };
  var days = Math.max(1, Number(dayCount) || 1);
  var diff = (days - 1) * 1440 + aMin - dMin;
  if (diff < 0) diff += 1440;
  return { hours: Math.floor(diff / 60), minutes: diff % 60 };
}

function computeRouteDistKm_(route, stCoords) {
  var total = 0;
  var prev = null;
  for (var i = 0; i < route.length; i++) {
    var s = route[i];
    var geo = s.stationCode ? (stCoords[s.stationCode] || {}) : {};
    var lat = s.lat != null ? s.lat : geo.lat;
    var lon = s.lon != null ? s.lon : geo.lon;
    if (lat != null && lon != null) {
      if (prev) total += haversineKm_(prev.lat, prev.lon, lat, lon) * 1.2;
      prev = { lat: lat, lon: lon };
    }
  }
  return total > 0 ? Math.round(total * 10) / 10 : 0;
}

function fetchBetweenFromExternalApi_(fromCode, toCode, stCoords) {
  var fGeo = stCoords[fromCode];
  var tGeo = stCoords[toCode];
  var approxDist = (fGeo && tGeo && fGeo.lat != null && tGeo.lat != null)
    ? Math.round(haversineKm_(fGeo.lat, fGeo.lon, tGeo.lat, tGeo.lon) * 1.2 * 10) / 10
    : 0;

  var out = [];
  var cache = CacheService.getScriptCache();
  var apiCacheKey = 'rr_between_v1_' + fromCode + '_' + toCode;
  var cachedRaw = cache.get(apiCacheKey);
  if (cachedRaw) {
    try {
      var parsedCached = JSON.parse(cachedRaw);
      if (Array.isArray(parsedCached) && parsedCached.length) return parsedCached;
    } catch (_) {}
  }

  var paths = [
    '/v1/trains/between/' + encodeURIComponent(fromCode) + '/' + encodeURIComponent(toCode),
    '/v1/legacy/trains/between?from=' + encodeURIComponent(fromCode) + '&to=' + encodeURIComponent(toCode)
  ];

  for (var pIdx = 0; pIdx < paths.length; pIdx++) {
    var rrJson = fetchRailRadarJson_(paths[pIdx]);
    if (!rrJson) continue;
    var list = (rrJson.data && Array.isArray(rrJson.data.trains)) ? rrJson.data.trains
      : (Array.isArray(rrJson.data) ? rrJson.data
      : (Array.isArray(rrJson.trains) ? rrJson.trains : []));
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      var trObj = item.train || item;
      var num = String(trObj.number || trObj.trainNumber || trObj.train_number || '').replace(/\D/g, '');
      if (!num) continue;
      var name = String(trObj.name || trObj.trainName || trObj.train_name || '').trim();
      var fromStop = item.fromStop || item.from || item.source || {};
      var toStop = item.toStop || item.to || item.destination || {};
      var dep = normHHmm_(fromStop.departure || fromStop.scheduledDeparture || item.departure || item.from_std) || '--:--';
      var arr = normHHmm_(toStop.arrival || toStop.scheduledArrival || item.arrival || item.to_sta) || '--:--';
      var dist = Number(item.distance || item.distanceKm) || approxDist;
      var meta = extractTrainMeta_(item);
      out.push({
        train: {
          number: num,
          name: name,
          type: meta.trainType,
          zone: meta.zone,
          sourceCode: meta.sourceCode,
          sourceName: meta.sourceName,
          destCode: meta.destCode,
          destName: meta.destName
        },
        from: { departure: dep, stationCode: fromCode },
        to: { arrival: arr, stationCode: toCode },
        distance: dist,
        runDays: meta.runningDays || '',
        _meta: meta
      });
    }
    if (out.length) break;
  }

  if (!out.length) {
    var props = PropertiesService.getScriptProperties();
    var rapidKey = props.getProperty('RAPIDAPI_KEY');
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
          var arrList = json.data || [];
          for (var j = 0; j < arrList.length; j++) {
            var t = arrList[j];
            var tNo = String(t.train_number || t.trainNumber || '').replace(/\D/g, '');
            if (!tNo) continue;
            var rDays = normalizeRunDays_(t.run_days || t.runningDays || t.runDays);
            out.push({
              train: {
                number: tNo,
                name: String(t.train_name || t.trainName || '').trim(),
                type: String(t.train_type || ''),
                sourceCode: String(t.train_origin_station_code || ''),
                sourceName: String(t.train_origin_station || ''),
                destCode: String(t.train_destination_station_code || ''),
                destName: String(t.train_destination_station || '')
              },
              from: { departure: normHHmm_(t.from_std || t.from_time) || '--:--', stationCode: fromCode },
              to: { arrival: normHHmm_(t.to_sta || t.to_time) || '--:--', stationCode: toCode },
              distance: Number(t.distance) || approxDist,
              runDays: rDays,
              _meta: {
                trainName: String(t.train_name || t.trainName || '').trim(),
                trainType: String(t.train_type || ''),
                sourceCode: String(t.train_origin_station_code || ''),
                sourceName: String(t.train_origin_station || ''),
                destCode: String(t.train_destination_station_code || ''),
                destName: String(t.train_destination_station || ''),
                runningDays: rDays
              }
            });
          }
        }
      } catch (_) {}
    }
  }

  out.sort(function (a, b) {
    var ma = parseTimeMins_(a.from.departure);
    var mb = parseTimeMins_(b.from.departure);
    if (ma == null) ma = 9999;
    if (mb == null) mb = 9999;
    return ma !== mb ? ma - mb : String(a.train.number).localeCompare(String(b.train.number));
  });
  if (out.length) {
    try {
      var serialized = JSON.stringify(out);
      if (serialized.length < 95000) cache.put(apiCacheKey, serialized, 1800);
    } catch (_) {}
  }
  return out;
}

function fetchLiveAndRouteFromExternalApi_(trainNo, stMap) {
  // পূর্ণ রুটের সাথে স্টেশন কোঅর্ডিনেট ও নন-স্টপ পাস-থ্রু স্টেশনগুলো (isHalt: false) পাওয়ার জন্য includeCoordinates=true সহ কল করি
  var livePaths = [
    '/v1/trains/' + encodeURIComponent(trainNo) + '/live?includeCoordinates=true',
    '/v1/trains/' + encodeURIComponent(trainNo)
  ];

  var bestMeta = null;
  var bestName = '';
  var bestRoute = [];
  var bestLoc = null;
  var isEstimated = false;

  for (var idx = 0; idx < livePaths.length; idx++) {
    var rrJson = fetchRailRadarJson_(livePaths[idx]);
    if (!rrJson) continue;
    var d = rrJson.data || rrJson;
    if (!d) continue;

    var meta = extractTrainMeta_(d, bestMeta);
    bestMeta = meta;
    if (meta.trainName && !bestName) bestName = meta.trainName;
    var rawRoute = Array.isArray(d.route) ? d.route : [];
    var route = normalizeExternalRoute_(rawRoute, stMap);
    if (route.length > bestRoute.length) bestRoute = route;

    if (idx === 0) {
      // যদি /live রুট না দেয় (যেমন আজকে ট্রেন না চললে), তবুও /v1/trains/{trainNo} থেকে পূর্ণ স্ট্যাটিক রুট নিই
      if (bestRoute.length < 2) {
        var schedJson = fetchRailRadarJson_('/v1/trains/' + encodeURIComponent(trainNo));
        if (schedJson) {
          var sd0 = schedJson.data || schedJson;
          var schedMeta = extractTrainMeta_(sd0, bestMeta);
          bestMeta = schedMeta;
          if (!bestName && schedMeta.trainName) bestName = schedMeta.trainName;
          var sRoute = normalizeExternalRoute_(Array.isArray(sd0.route) ? sd0.route : [], stMap);
          if (sRoute.length > bestRoute.length) bestRoute = sRoute;
        }
      }
      bestLoc = resolveRailRadarCurrentLocation_(d, bestRoute, stMap, bestMeta);
    }

    if (bestRoute.length >= 2) break;
  }

  if (bestRoute.length || bestLoc) {
    if (!bestLoc && bestRoute.length) {
      bestLoc = estimateLocationFromRoute_(bestRoute, bestMeta);
      isEstimated = true;
    }
    return {
      trainName: bestName,
      meta: bestMeta,
      currentLocation: bestLoc,
      route: bestRoute,
      isEstimated: isEstimated
    };
  }

  // RapidAPI fallback
  var props = PropertiesService.getScriptProperties();
  var rapidKey = props.getProperty('RAPIDAPI_KEY');
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
        var rArr = normalizeExternalRoute_(sd.route || [], stMap);
        if (rArr.length) {
          var rMeta = extractTrainMeta_(sd);
          return {
            trainName: String(sd.trainName || '').trim(),
            meta: rMeta,
            currentLocation: estimateLocationFromRoute_(rArr, rMeta),
            route: rArr,
            isEstimated: true
          };
        }
      }
    } catch (_) {}
  }

  return null;
}

function resolveRailRadarCurrentLocation_(d, route, stMap, meta) {
  var nextHaltObj = (d && d.nextHalt && typeof d.nextHalt === 'object') ? d.nextHalt : null;
  var nextHaltName = nextHaltObj ? String(nextHaltObj.stationName || nextHaltObj.stationCode || '').trim() : '';

  if (d && d.currentLocation && typeof d.currentLocation === 'object') {
    var cl = d.currentLocation;
    var clCode = String(cl.stationCode || cl.code || '').trim().toUpperCase();
    var geo = clCode ? (stMap[clCode] || {}) : {};
    var clCoords = (cl.coordinates && typeof cl.coordinates === 'object') ? cl.coordinates : null;
    var rawLat = clCoords ? clCoords.lat : (cl.lat != null ? cl.lat : (cl.latitude != null ? cl.latitude : geo.lat));
    var rawLon = clCoords ? (clCoords.lng != null ? clCoords.lng : clCoords.lon) : (cl.lng != null ? cl.lng : (cl.lon != null ? cl.lon : (cl.longitude != null ? cl.longitude : geo.lon)));
    var lat = rawLat != null ? Number(rawLat) : null;
    var lon = rawLon != null ? Number(rawLon) : null;

    var curRouteIdx = -1;
    if (clCode && route.length) {
      for (var ri = 0; ri < route.length; ri++) {
        if (route[ri].stationCode === clCode) { curRouteIdx = ri; break; }
      }
    }
    var stMatch = curRouteIdx >= 0 ? route[curRouteIdx] : null;
    var nxtMatch = (curRouteIdx >= 0 && curRouteIdx + 1 < route.length) ? route[curRouteIdx + 1] : null;
    if (lat == null && stMatch) lat = stMatch.lat;
    if (lon == null && stMatch) lon = stMatch.lon;

    var outNext = String(cl.nextStationName || nextHaltName || (nxtMatch ? (nxtMatch.stationName || nxtMatch.stationCode) : '') || '').trim() || null;
    if ((lat != null && isFinite(lat) && lon != null && isFinite(lon)) || clCode) {
      return {
        stationCode: clCode || (stMatch && stMatch.stationCode) || null,
        stationName: String(cl.stationName || cl.name || geo.name || (stMatch && stMatch.stationName) || clCode || ''),
        sequence: cl.sequence || (stMatch ? stMatch.sequence : null),
        lat: (lat != null && isFinite(lat)) ? lat : null,
        lon: (lon != null && isFinite(lon)) ? lon : null,
        speedKmh: Number(cl.speedKmh || cl.speed) || 0,
        bearingDegrees: cl.bearingDegrees != null ? Number(cl.bearingDegrees) : null,
        segmentProgress: Number(cl.segmentProgress) || (cl.status === 'departed' ? 0.35 : 0),
        nextStationName: outNext
      };
    }
  }

  var liveStatus = String((d && d.status) || '').toLowerCase();
  if (liveStatus === 'completed' && route.length) {
    var lastSt = route[route.length - 1];
    return {
      stationCode: lastSt.stationCode || null,
      stationName: lastSt.stationName || lastSt.stationCode || '',
      sequence: lastSt.sequence || route.length,
      lat: lastSt.lat,
      lon: lastSt.lon,
      speedKmh: 0,
      bearingDegrees: null,
      segmentProgress: 0,
      nextStationName: null
    };
  }

  var curCode = String((d && (d.currentStation || d.currentStationCode)) || '').trim().toUpperCase();
  if (!curCode && route.length) {
    for (var i = route.length - 1; i >= 0; i--) {
      if (route[i]._status === 'arrived' || route[i]._status === 'departed' || route[i]._status === 'passed') {
        curCode = route[i].stationCode;
        break;
      }
    }
  }
  if (!curCode) return null;

  var hitIdx = -1;
  for (var j = 0; j < route.length; j++) {
    if (route[j].stationCode === curCode) { hitIdx = j; break; }
  }
  var stHit = hitIdx >= 0 ? route[hitIdx] : null;
  var nextHit = (hitIdx >= 0 && hitIdx + 1 < route.length) ? route[hitIdx + 1] : null;
  var geoHit = stMap[curCode] || {};
  var finalLat = stHit && stHit.lat != null ? stHit.lat : (geoHit.lat != null ? geoHit.lat : null);
  var finalLon = stHit && stHit.lon != null ? stHit.lon : (geoHit.lon != null ? geoHit.lon : null);
  if (finalLat == null || finalLon == null) return null;

  return {
    stationCode: curCode,
    stationName: (stHit && stHit.stationName) || geoHit.name || curCode,
    sequence: stHit ? stHit.sequence : 1,
    lat: finalLat,
    lon: finalLon,
    speedKmh: (stHit && stHit._status === 'departed') ? 55 : 0,
    bearingDegrees: null,
    segmentProgress: (stHit && stHit._status === 'departed') ? 0.35 : 0,
    nextStationName: nextHaltName || (nextHit ? (nextHit.stationName || nextHit.stationCode) : null)
  };
}

function normalizeExternalRoute_(rawRoute, stMap) {
  stMap = stMap || {};
  var out = [];
  if (!Array.isArray(rawRoute) || !rawRoute.length) return out;

  // রুটটিতে আসলেই ভিন্ন Arr/Dep টাইম আছে নাকি সব স্টেশনে একই ফরম্যাট (যেমন MEMU) তা আগে দেখে নিই
  var hasDifferentArrDep = false;
  for (var p = 0; p < rawRoute.length; p++) {
    var ps = rawRoute[p] || {};
    var pArr = normHHmm_(ps.scheduledArrival || ps.arrival || ps.std_arr || ps.actualArrival);
    var pDep = normHHmm_(ps.scheduledDeparture || ps.departure || ps.std_dep || ps.actualDeparture);
    if (pArr && pDep && pArr !== pDep) {
      hasDifferentArrDep = true;
      break;
    }
  }

  for (var i = 0; i < rawRoute.length; i++) {
    var st = rawRoute[i] || {};
    var stObj = (st.station && typeof st.station === 'object') ? st.station : {};
    var code = String(st.stationCode || st.station_code || st.code || stObj.code || stObj.stationCode || '').trim().toUpperCase();
    var geo = code ? (stMap[code] || {}) : {};
    var name = String(st.stationName || st.station_name || st.name || stObj.name || stObj.stationName || geo.name || code).trim();
    if (!code && !name) continue;

    var arr = normHHmm_(st.scheduledArrival || st.arrival || st.std_arr || st.actualArrival || st.eta) || null;
    var dep = normHHmm_(st.scheduledDeparture || st.departure || st.std_dep || st.actualDeparture || st.etd) || null;
    var isTerminal = (i === 0 || i === rawRoute.length - 1);
    var isHalt = true;
    if (!isTerminal) {
      if (typeof st.isHalt === 'boolean') {
        isHalt = st.isHalt;
      } else if (st.isHalt === 'false' || st.isHalt === 0) {
        isHalt = false;
      } else if (arr && dep && arr === dep && hasDifferentArrDep) {
        isHalt = false;
      }
    }

    var sched = normHHmm_(st.scheduledTime) || (i === 0 ? (dep || arr) : (arr || dep)) || '--:--';
    var rawLat = st.lat != null ? st.lat : (st.latitude != null ? st.latitude : (stObj.lat != null ? stObj.lat : (stObj.latitude != null ? stObj.latitude : geo.lat)));
    var rawLon = st.lng != null ? st.lng : (st.lon != null ? st.lon : (st.longitude != null ? st.longitude : (stObj.lng != null ? stObj.lng : (stObj.lon != null ? stObj.lon : (stObj.longitude != null ? stObj.longitude : geo.lon)))));
    var lat = rawLat != null && rawLat !== '' ? Number(rawLat) : null;
    var lon = rawLon != null && rawLon !== '' ? Number(rawLon) : null;
    var dayVal = parseInt(st.day || st.arrivalDay || st.departureDay, 10) || null;

    out.push({
      stationCode: code,
      stationName: name,
      sequence: out.length + 1,
      lat: (lat != null && isFinite(lat)) ? lat : null,
      lon: (lon != null && isFinite(lon)) ? lon : null,
      scheduledTime: sched,
      arrival: (i === 0 && dep) ? dep : (arr || sched),
      departure: dep || sched,
      day: dayVal,
      isHalt: isHalt,
      _status: String(st.status || '').toLowerCase()
    });
  }
  if (out.length) {
    out[0].isHalt = true;
    out[out.length - 1].isHalt = true;
  }
  return out;
}

function syncExternalTrainToSheets_(ss, trainNo, trainName, route, meta, forceTimeSync) {
  if (!route || route.length < 2) return;
  try {
    var m = meta || {};
    var stopsArr = [], stations = [], sched = [];
    var day = 1, prevMin = null;
    var firstHalt = route[0], lastHalt = route[route.length - 1];
    for (var fi = 0; fi < route.length; fi++) { if (route[fi].isHalt !== false) { firstHalt = route[fi]; break; } }
    for (var li = route.length - 1; li >= 0; li--) { if (route[li].isHalt !== false) { lastHalt = route[li]; break; } }

    for (var i = 0; i < route.length; i++) {
      var s = route[i];
      if (s.isHalt !== false && s.stationCode) stopsArr.push(s.stationCode);
      if (s.stationCode) {
        var stObj = { stationCode: s.stationCode, stationName: s.stationName || s.stationCode };
        if (s.lat != null && s.lon != null) { stObj.latitude = s.lat; stObj.longitude = s.lon; }
        stations.push(stObj);
      }
      var minNow = parseTimeMins_(i === 0 ? (s.departure || s.scheduledTime) : (s.arrival || s.scheduledTime || s.departure));
      if (prevMin != null && minNow != null && minNow + 120 < prevMin) day++;
      if (minNow != null) prevMin = minNow;
      var arrVal = (s.arrival && s.arrival !== '--:--') ? s.arrival : 'None';
      var depVal = (s.departure && s.departure !== '--:--') ? s.departure : 'None';
      if (i === 0) arrVal = 'None';
      if (i === route.length - 1) depVal = 'None';
      if (s.isHalt === false) {
        var passT = s.scheduledTime && s.scheduledTime !== '--:--' ? s.scheduledTime : (arrVal !== 'None' ? arrVal : depVal);
        if (passT && passT !== 'None') { arrVal = passT; depVal = passT; }
      }
      sched.push({
        sequence: i + 1,
        trainNumber: trainNo,
        trainName: trainName || '',
        stationCode: s.stationCode,
        stationName: s.stationName || s.stationCode,
        arrival: arrVal,
        departure: depVal,
        day: s.day || day,
        isHalt: s.isHalt !== false ? 'Y' : 'N'
      });
    }
    var depOrigin = firstHalt.departure || firstHalt.scheduledTime || m.originDeparture || '';
    var arrDest = lastHalt.arrival || lastHalt.scheduledTime || m.destArrival || '';
    var durObj = computeDurationHrMin_(depOrigin, arrDest, lastHalt.day || day, m.durationMinutesTotal);

    if (stations.length) upsertStationsSheet_(ss.getSheetByName('Station'), stations);
    upsertTrainSheet_(ss.getSheetByName('Train'), [{
      trainNumber: trainNo,
      trainName: trainName || '',
      trainType: m.trainType || '',
      zone: m.zone || '',
      fromCode: firstHalt.stationCode || m.sourceCode || '',
      fromName: firstHalt.stationName || m.sourceName || '',
      departure: depOrigin,
      toCode: lastHalt.stationCode || m.destCode || '',
      toName: lastHalt.stationName || m.destName || '',
      arrival: arrDest,
      durationHours: durObj.hours,
      durationMinutes: durObj.minutes,
      distanceKm: m.totalDistanceKm || 0,
      returnTrain: m.returnTrain || '',
      firstAc: m.firstAc,
      secondAc: m.secondAc,
      thirdAc: m.thirdAc,
      chairCar: m.chairCar,
      firstClass: m.firstClass,
      sleeper: m.sleeper,
      routePointsCount: route.length,
      trainStops: stopsArr.join(', '),
      runningDays: m.runningDays || ''
    }], false);
    upsertScheduleSheet_(ss.getSheetByName('Schedule'), sched, !!forceTimeSync);
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

function estimateLocationFromRoute_(route, meta) {
  if (!route || !route.length) return null;
  var istNow = new Date(Date.now() + 19800000);
  var nowMin = istNow.getUTCHours() * 60 + istNow.getUTCMinutes();
  var nowDow = istNow.getUTCDay();

  var absMins = [];
  var curDay = 1;
  var prevMod = null;
  for (var i = 0; i < route.length; i++) {
    var st = route[i];
    var m = (i === 0)
      ? (parseTimeMins_(st.departure) != null ? parseTimeMins_(st.departure) : (parseTimeMins_(st.scheduledTime) != null ? parseTimeMins_(st.scheduledTime) : parseTimeMins_(st.arrival)))
      : (parseTimeMins_(st.arrival) != null ? parseTimeMins_(st.arrival) : (parseTimeMins_(st.scheduledTime) != null ? parseTimeMins_(st.scheduledTime) : parseTimeMins_(st.departure)));
    if (st.day != null && Number(st.day) >= 1) {
      curDay = Number(st.day);
    } else if (prevMod != null && m != null && m + 120 < prevMod) {
      curDay++;
    }
    if (m != null) prevMod = m;
    absMins.push(m != null ? ((curDay - 1) * 1440 + m) : null);
  }

  var firstAbs = absMins[0];
  var lastAbs = absMins[absMins.length - 1];
  for (var fi = 0; fi < absMins.length && firstAbs == null; fi++) firstAbs = absMins[fi];
  for (var li = absMins.length - 1; li >= 0 && lastAbs == null; li--) lastAbs = absMins[li];

  var allowedDays = parseRunningDaysSet_(meta && meta.runningDays);
  var activeIdx = 0;
  var segFrac = 0;
  var isRunningNow = false;

  if (firstAbs != null && lastAbs != null && lastAbs > firstAbs && route.length >= 2) {
    var maxDays = Math.ceil((lastAbs + 120) / 1440);
    var targetMin = null;
    for (var dayOff = 0; dayOff <= maxDays; dayOff++) {
      var cand = nowMin + dayOff * 1440;
      if (cand >= firstAbs && cand <= lastAbs) {
        if (allowedDays) {
          var startDow = (nowDow - dayOff + 70) % 7;
          if (!allowedDays[startDow]) continue;
        }
        targetMin = cand;
        break;
      }
    }
    if (targetMin != null) {
      isRunningNow = true;
      var lastValidIdx = 0;
      for (var j = 0; j < absMins.length; j++) {
        if (absMins[j] == null) continue;
        if (absMins[j] <= targetMin) {
          lastValidIdx = j;
        } else {
          var prevT = absMins[lastValidIdx];
          var nextT = absMins[j];
          activeIdx = lastValidIdx;
          if (prevT != null && nextT > prevT) {
            segFrac = Math.min(1, Math.max(0, (targetMin - prevT) / (nextT - prevT)));
          }
          break;
        }
      }
      if (targetMin >= lastAbs) {
        activeIdx = route.length - 1;
        segFrac = 0;
      }
    }
  }

  var cur = route[activeIdx];
  var nxt = route[activeIdx + 1] || null;
  var lat = cur.lat;
  var lon = cur.lon;
  var bearing = null;
  var estSpeed = 0;
  if (isRunningNow && cur.lat != null && cur.lon != null && nxt && nxt.lat != null && nxt.lon != null) {
    lat = cur.lat + (nxt.lat - cur.lat) * segFrac;
    lon = cur.lon + (nxt.lon - cur.lon) * segFrac;
    var dLat = nxt.lat - cur.lat;
    var dLon = nxt.lon - cur.lon;
    if (Math.abs(dLat) > 1e-6 || Math.abs(dLon) > 1e-6) {
      var toRad = Math.PI / 180;
      var y = Math.sin(dLon * toRad) * Math.cos(nxt.lat * toRad);
      var x = Math.cos(cur.lat * toRad) * Math.sin(nxt.lat * toRad) -
        Math.sin(cur.lat * toRad) * Math.cos(nxt.lat * toRad) * Math.cos(dLon * toRad);
      bearing = (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
    }
    var segDist = haversineKm_(cur.lat, cur.lon, nxt.lat, nxt.lon) * 1.2;
    var tA = absMins[activeIdx];
    var tB = absMins[activeIdx + 1];
    if (tA != null && tB != null && tB > tA && segDist > 0.2) {
      estSpeed = Math.min(130, Math.max(20, Math.round((segDist / ((tB - tA) / 60)) * 10) / 10));
    } else if (segDist > 0.2) {
      estSpeed = 48;
    }
  }

  return {
    stationCode: cur.stationCode || null,
    stationName: cur.stationName || cur.stationCode || '',
    sequence: cur.sequence || (activeIdx + 1),
    lat: lat,
    lon: lon,
    speedKmh: estSpeed,
    bearingDegrees: bearing,
    segmentProgress: segFrac,
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
      stats.scheduleUpserted = upsertScheduleSheet_(ss.getSheetByName('Schedule'), data.schedule, !!data.authoritativeSync);
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
  var lastCol = Math.max(sheet.getLastColumn(), 23);
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
      'Route_Points_Count', 'Train_Stops', 'Running_Days'
    ]];
    sheet.getRange(1, 1, 1, 23).setValues(header);
    values = header;
    disp = header;
    lastRow = 1;
  }

  var hm = headerMap_(disp[0]);
  if (hm.runningdays == null && hm.rundays == null) {
    var daysCol = 22;
    for (var hc = 0; hc < disp[0].length; hc++) {
      if (!String(disp[0][hc] || '').trim()) { daysCol = hc; break; }
    }
    if (daysCol + 1 > lastCol) lastCol = daysCol + 1;
    sheet.getRange(1, daysCol + 1).setValue('Running_Days');
    disp[0][daysCol] = 'Running_Days';
    hm.runningdays = daysCol;
  }

  var cNum = hm.trainnumber != null ? hm.trainnumber : 0;
  var cName = hm.trainname != null ? hm.trainname : 1;
  var cType = hm.traintype != null ? hm.traintype : 2;
  var cZone = hm.zone != null ? hm.zone : 3;
  var cFromC = hm.fromstationcode != null ? hm.fromstationcode : 4;
  var cFromN = hm.fromstationname != null ? hm.fromstationname : 5;
  var cDep = hm.departure != null ? hm.departure : 6;
  var cToC = hm.tostationcode != null ? hm.tostationcode : 7;
  var cToN = hm.tostationname != null ? hm.tostationname : 8;
  var cArr = hm.arrival != null ? hm.arrival : 9;
  var cDurH = hm.durationhours != null ? hm.durationhours : 10;
  var cDurM = hm.durationminutes != null ? hm.durationminutes : 11;
  var cDist = hm.distancekm != null ? hm.distancekm : 12;
  var cRet = hm.returntrain != null ? hm.returntrain : 13;
  var c1A = hm.firstac != null ? hm.firstac : 14;
  var c2A = hm.secondac != null ? hm.secondac : 15;
  var c3A = hm.thirdac != null ? hm.thirdac : 16;
  var cCC = hm.chaircar != null ? hm.chaircar : 17;
  var cFC = hm.firstclass != null ? hm.firstclass : 18;
  var cSL = hm.sleeper != null ? hm.sleeper : 19;
  var cPts = hm.routepointscount != null ? hm.routepointscount : 20;
  var cStops = hm.trainstops != null ? hm.trainstops : 21;
  var cDays = hm.runningdays != null ? hm.runningdays : (hm.rundays != null ? hm.rundays : 22);

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
    var normDays = normalizeRunDays_(t.runningDays);
    var rowIdx = rowByTrain[k];

    if (rowIdx != null) {
      var row = values[rowIdx];
      var dRow = disp[rowIdx];
      while (row.length < lastCol) { row.push(''); dRow.push(''); }
      var changed = false;

      var exStops = String(dRow[cStops] || '').trim();
      var exStopsCount = exStops ? exStops.split(',').length : 0;
      var exPts = parseInt(dRow[cPts], 10) || 0;
      // পুরানো সার্চ-সেশন থেকে শুধু ২টি স্টপ ("BWN, AMP") সেভ হয়ে থাকলে সেটিকে অসম্পূর্ণ ধরে আপডেট allow করি
      var forceReplaceEndpoints = !onlyInsertIfMissingOrFillBlank || (exStopsCount <= 2 && exPts <= 2 && incomingStopsCount > 2);

      if (t.trainName && (isEmptyOrNone_(dRow[cName]) || (forceReplaceEndpoints && String(dRow[cName]).trim() !== t.trainName))) {
        row[cName] = t.trainName; changed = true;
      }
      if (t.trainType && (isEmptyOrNone_(dRow[cType]) || forceReplaceEndpoints)) {
        if (String(dRow[cType]).trim() !== String(t.trainType).trim()) { row[cType] = t.trainType; changed = true; }
      }
      if (t.zone && (isEmptyOrNone_(dRow[cZone]) || forceReplaceEndpoints)) {
        if (String(dRow[cZone]).trim() !== String(t.zone).trim()) { row[cZone] = t.zone; changed = true; }
      }
      if (forceReplaceEndpoints || isEmptyOrNone_(dRow[cFromC])) {
        if (t.fromCode && String(dRow[cFromC]).trim().toUpperCase() !== t.fromCode) { row[cFromC] = t.fromCode; changed = true; }
        if (t.fromName && String(dRow[cFromN]).trim() !== t.fromName) { row[cFromN] = t.fromName; changed = true; }
        var inDep = normHHmm_(t.departure);
        if (inDep && normHHmm_(dRow[cDep]) !== inDep) { row[cDep] = inDep; changed = true; }
        if (t.toCode && String(dRow[cToC]).trim().toUpperCase() !== t.toCode) { row[cToC] = t.toCode; changed = true; }
        if (t.toName && String(dRow[cToN]).trim() !== t.toName) { row[cToN] = t.toName; changed = true; }
        var inArr = normHHmm_(t.arrival);
        if (inArr && normHHmm_(dRow[cArr]) !== inArr) { row[cArr] = inArr; changed = true; }
      }
      if (t.durationHours !== '' && t.durationHours != null && isEmptyOrNone_(dRow[cDurH])) {
        row[cDurH] = Number(t.durationHours); changed = true;
      }
      if (t.durationMinutes !== '' && t.durationMinutes != null && isEmptyOrNone_(dRow[cDurM])) {
        row[cDurM] = Number(t.durationMinutes); changed = true;
      }
      if (t.distanceKm > 0 && !(Number(row[cDist]) > 0)) { row[cDist] = Number(t.distanceKm); changed = true; }
      if (t.returnTrain && isEmptyOrNone_(dRow[cRet])) { row[cRet] = t.returnTrain; changed = true; }
      if (t.firstAc !== '' && t.firstAc != null && isEmptyOrNone_(dRow[c1A])) { row[c1A] = t.firstAc; changed = true; }
      if (t.secondAc !== '' && t.secondAc != null && isEmptyOrNone_(dRow[c2A])) { row[c2A] = t.secondAc; changed = true; }
      if (t.thirdAc !== '' && t.thirdAc != null && isEmptyOrNone_(dRow[c3A])) { row[c3A] = t.thirdAc; changed = true; }
      if (t.chairCar !== '' && t.chairCar != null && isEmptyOrNone_(dRow[cCC])) { row[cCC] = t.chairCar; changed = true; }
      if (t.firstClass !== '' && t.firstClass != null && isEmptyOrNone_(dRow[cFC])) { row[cFC] = t.firstClass; changed = true; }
      if (t.sleeper !== '' && t.sleeper != null && isEmptyOrNone_(dRow[cSL])) { row[cSL] = t.sleeper; changed = true; }

      if (t.routePointsCount > 0 && (t.routePointsCount > exPts || forceReplaceEndpoints) && t.routePointsCount !== exPts) {
        row[cPts] = Number(t.routePointsCount); changed = true;
      }
      if (incomingStops && (isEmptyOrNone_(exStops) || incomingStopsCount > exStopsCount || (forceReplaceEndpoints && incomingStopsCount >= exStopsCount && exStops !== incomingStops))) {
        row[cStops] = incomingStops;
        changed = true;
      }
      if (normDays && (isEmptyOrNone_(dRow[cDays]) || (!onlyInsertIfMissingOrFillBlank && String(dRow[cDays]).trim() !== normDays))) {
        row[cDays] = normDays;
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
      newRow[cType] = t.trainType || '';
      newRow[cZone] = t.zone || '';
      newRow[cFromC] = t.fromCode || '';
      newRow[cFromN] = t.fromName || '';
      newRow[cDep] = normHHmm_(t.departure) || t.departure || '';
      newRow[cToC] = t.toCode || '';
      newRow[cToN] = t.toName || '';
      newRow[cArr] = normHHmm_(t.arrival) || t.arrival || '';
      if (t.durationHours !== '' && t.durationHours != null) newRow[cDurH] = Number(t.durationHours);
      if (t.durationMinutes !== '' && t.durationMinutes != null) newRow[cDurM] = Number(t.durationMinutes);
      if (t.distanceKm > 0) newRow[cDist] = Number(t.distanceKm);
      if (t.returnTrain) newRow[cRet] = t.returnTrain;
      if (t.firstAc !== '' && t.firstAc != null) newRow[c1A] = t.firstAc;
      if (t.secondAc !== '' && t.secondAc != null) newRow[c2A] = t.secondAc;
      if (t.thirdAc !== '' && t.thirdAc != null) newRow[c3A] = t.thirdAc;
      if (t.chairCar !== '' && t.chairCar != null) newRow[cCC] = t.chairCar;
      if (t.firstClass !== '' && t.firstClass != null) newRow[cFC] = t.firstClass;
      if (t.sleeper !== '' && t.sleeper != null) newRow[cSL] = t.sleeper;
      if (t.routePointsCount > 0) newRow[cPts] = Number(t.routePointsCount);
      newRow[cStops] = incomingStops;
      newRow[cDays] = normDays || '';
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

function upsertScheduleSheet_(sheet, schedItems, forceTimeSync) {
  if (!sheet || !schedItems.length) return 0;
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 9);
  var range = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol) : null;
  var values = range ? range.getValues() : [];
  var disp = range ? range.getDisplayValues() : [];

  if (!values.length) {
    var header = [['Schedule_ID', 'Train_Number', 'Train_Name', 'Station_Code', 'Station_Name', 'Arrival', 'Departure', 'Day', 'Is_Halt']];
    sheet.getRange(1, 1, 1, 9).setValues(header);
    values = header;
    disp = header;
    lastRow = 1;
  }

  var hm = headerMap_(disp[0]);
  if (hm.ishalt == null) {
    var hCol = 8;
    for (var hc = 0; hc < disp[0].length; hc++) {
      if (!String(disp[0][hc] || '').trim()) { hCol = hc; break; }
    }
    if (hCol + 1 > lastCol) lastCol = hCol + 1;
    sheet.getRange(1, hCol + 1).setValue('Is_Halt');
    disp[0][hCol] = 'Is_Halt';
    hm.ishalt = hCol;
  }

  var cId = hm.scheduleid != null ? hm.scheduleid : 0;
  var cTrainNo = hm.trainnumber != null ? hm.trainnumber : 1;
  var cTrainName = hm.trainname != null ? hm.trainname : 2;
  var cStCode = hm.stationcode != null ? hm.stationcode : 3;
  var cStName = hm.stationname != null ? hm.stationname : 4;
  var cArr = hm.arrival != null ? hm.arrival : 5;
  var cDep = hm.departure != null ? hm.departure : 6;
  var cDay = hm.day != null ? hm.day : 7;
  var cHalt = hm.ishalt != null ? hm.ishalt : 8;

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

    var rawArrStr = String(s.arrival == null ? '' : s.arrival).trim();
    var rawDepStr = String(s.departure == null ? '' : s.departure).trim();
    var inArr = normHHmm_(rawArrStr);
    var inDep = normHHmm_(rawDepStr);
    var explicitNoneArr = /^none$/i.test(rawArrStr);
    var explicitNoneDep = /^none$/i.test(rawDepStr);
    var inHalt = s.isHalt === false || String(s.isHalt).toUpperCase() === 'N' ? 'N'
      : (s.isHalt === true || String(s.isHalt).toUpperCase() === 'Y' ? 'Y' : '');

    if (rowIdx != null) {
      var row = values[rowIdx];
      var dRow = disp[rowIdx];
      while (row.length < lastCol) { row.push(''); dRow.push(''); }
      var changed = false;
      if (s.trainName && String(dRow[cTrainName]).trim() !== s.trainName) { row[cTrainName] = s.trainName; changed = true; }
      if (s.stationName && String(dRow[cStName]).trim() !== s.stationName) { row[cStName] = s.stationName; changed = true; }

      if (forceTimeSync) {
        if (explicitNoneArr && !isEmptyOrNone_(dRow[cArr])) { row[cArr] = 'None'; changed = true; }
        else if (inArr && normHHmm_(dRow[cArr]) !== inArr) { row[cArr] = inArr; changed = true; }
        if (explicitNoneDep && !isEmptyOrNone_(dRow[cDep])) { row[cDep] = 'None'; changed = true; }
        else if (inDep && normHHmm_(dRow[cDep]) !== inDep) { row[cDep] = inDep; changed = true; }
        if (s.day && Number(row[cDay]) !== Number(s.day)) { row[cDay] = Number(s.day); changed = true; }
      } else {
        if (inArr && isEmptyOrNone_(dRow[cArr]) && Number(s.sequence) > 1) { row[cArr] = inArr; changed = true; }
        if (inDep && isEmptyOrNone_(dRow[cDep])) { row[cDep] = inDep; changed = true; }
        if (s.day && isEmptyOrNone_(dRow[cDay])) { row[cDay] = Number(s.day); changed = true; }
      }
      if (inHalt && String(dRow[cHalt] || '').trim().toUpperCase() !== inHalt) {
        row[cHalt] = inHalt;
        changed = true;
      }
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
      newRow[cHalt] = inHalt;
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

/* ==================== RUNNING DAYS NORMALIZER ==================== */

function normalizeRunDays_(raw, bitmap) {
  if ((raw == null || raw === '') && bitmap != null && isFinite(Number(bitmap))) {
    var bm = parseInt(bitmap, 10);
    if (bm > 0) {
      var bmActive = [false, false, false, false, false, false, false];
      // RailRadar runningDaysBitmap: bit 0 = Mon, bit 1 = Tue, ..., bit 5 = Sat, bit 6 = Sun (127 = Daily)
      for (var bi0 = 0; bi0 < 7; bi0++) {
        if ((bm & (1 << bi0)) !== 0) bmActive[(bi0 + 1) % 7] = true;
      }
      var bmOut = [];
      for (var bd = 0; bd < 7; bd++) {
        if (bmActive[bd]) bmOut.push(DAY_CODES[bd]);
      }
      if (bmOut.length === 7) return 'Daily';
      if (bmOut.length) return bmOut.join(', ');
    }
  }

  if (raw == null || raw === '') return '';
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    if (raw.allDays === true && !raw.days) return 'Daily';
    if (raw.days != null) return normalizeRunDays_(raw.days, bitmap);
  }
  if (typeof raw === 'string') {
    var trimmed = raw.trim();
    if (!trimmed) return '';
    if (/month/i.test(trimmed)) return 'Monthly';
    if (/^(daily|all\s*days|everyday|every\s*day)$/i.test(trimmed)) return 'Daily';
  }
  var active = [false, false, false, false, false, false, false];
  var matched = false;
  var mapToken = function (tok) {
    var t = String(tok || '').toLowerCase().trim();
    if (!t) return;
    if (t === '0' || t === '7' || /^sun/.test(t) || t === 'su') { active[0] = true; matched = true; }
    else if (t === '1' || /^mon/.test(t) || t === 'm') { active[1] = true; matched = true; }
    else if (t === '2' || /^tue/.test(t) || t === 'tu') { active[2] = true; matched = true; }
    else if (t === '3' || /^wed/.test(t) || t === 'w') { active[3] = true; matched = true; }
    else if (t === '4' || /^thu/.test(t) || t === 'th') { active[4] = true; matched = true; }
    else if (t === '5' || /^fri/.test(t) || t === 'f') { active[5] = true; matched = true; }
    else if (t === '6' || /^sat/.test(t) || t === 'sa') { active[6] = true; matched = true; }
  };

  if (Array.isArray(raw)) {
    if (raw.length === 7 && (typeof raw[0] === 'boolean' || raw[0] === 0 || raw[0] === 1)) {
      for (var bi = 0; bi < 7; bi++) {
        if (raw[bi]) { active[(bi + 1) % 7] = true; matched = true; }
      }
    } else {
      for (var i = 0; i < raw.length; i++) mapToken(raw[i]);
    }
  } else if (typeof raw === 'object') {
    for (var k in raw) {
      if (raw[k]) mapToken(k);
    }
  } else {
    var str = String(raw).trim();
    if (/^[01YNyn]{7}$/.test(str)) {
      for (var si = 0; si < 7; si++) {
        var ch = str.charAt(si).toUpperCase();
        if (ch === '1' || ch === 'Y') { active[(si + 1) % 7] = true; matched = true; }
      }
    } else {
      var parts = str.split(/[,;|/\s]+/);
      for (var pi = 0; pi < parts.length; pi++) mapToken(parts[pi]);
    }
  }

  if (!matched) {
    if (bitmap != null && isFinite(Number(bitmap))) return normalizeRunDays_('', bitmap);
    return typeof raw === 'string' ? raw.trim() : '';
  }
  var res = [];
  for (var di = 0; di < 7; di++) {
    if (active[di]) res.push(DAY_CODES[di]);
  }
  if (res.length === 7) return 'Daily';
  return res.join(', ');
}

function parseRunningDaysSet_(str) {
  var norm = normalizeRunDays_(str);
  if (!norm || /month/i.test(norm)) return null;
  if (norm === 'Daily') return { 0: true, 1: true, 2: true, 3: true, 4: true, 5: true, 6: true };
  var set = {};
  var count = 0;
  var parts = norm.split(/[,;\s]+/);
  for (var i = 0; i < parts.length; i++) {
    var p = parts[i].toLowerCase().trim();
    for (var d = 0; d < 7; d++) {
      if (p === DAY_CODES[d].toLowerCase()) { set[d] = true; count++; break; }
    }
  }
  return count > 0 ? set : null;
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
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?Z$/i.test(s)) {
    var dUtc = new Date(s);
    if (!isNaN(dUtc.getTime())) {
      var ist = new Date(dUtc.getTime() + 19800000);
      var ih = ist.getUTCHours(), im = ist.getUTCMinutes();
      return (ih < 10 ? '0' + ih : '' + ih) + ':' + (im < 10 ? '0' + im : '' + im);
    }
  }
  var iso = s.match(/T(\d{2}):(\d{2})/);
  if (iso) return iso[1] + ':' + iso[2];
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

function parseHaltCell_(raw) {
  var s = String(raw == null ? '' : raw).trim().toLowerCase();
  if (!s) return null;
  if (s === 'n' || s === 'no' || s === 'false' || s === '0') return false;
  if (s === 'y' || s === 'yes' || s === 'true' || s === '1') return true;
  return null;
}

function isBetweenScheduleStop_(arrHHmm, depHHmm, explicitHalt, stCode, metaStopSet) {
  if (!arrHHmm && !depHHmm) return false;
  if (explicitHalt !== null) return explicitHalt;
  if (metaStopSet) return !!metaStopSet[stCode];
  if (arrHHmm && depHHmm && arrHHmm === depHHmm) return false;
  return true;
}

function isScheduledStopTimes_(arrHHmm, depHHmm) {
  if (!arrHHmm && !depHHmm) return false;
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
