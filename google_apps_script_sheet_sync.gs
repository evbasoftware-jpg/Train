/**
 * Google Apps Script Auto-Sync Handler for Train Live Location
 * Spreadsheet ID: 137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA
 *
 * আপনার Google Apps Script প্রোজেক্টের (API_URL) doPost(e)-এর ভেতরে এটি যুক্ত করুন:
 *
 *   function doPost(e) {
 *     var data = JSON.parse(e.postData.contents || '{}');
 *     if (data.action === 'syncSheetData') {
 *       return handleSyncSheetData_(data);
 *     }
 *     // ... আপনার আগের 'gps' বা অন্যান্য অ্যাকশন কোড ...
 *   }
 */

var DEFAULT_TRAIN_SPREADSHEET_ID = '137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA';

function handleSyncSheetData_(data) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) {
    return jsonResponse_({ success: false, error: 'Busy' });
  }
  try {
    var sheetId = (data && data.sheetId) ? String(data.sheetId) : DEFAULT_TRAIN_SPREADSHEET_ID;
    var ss = SpreadsheetApp.openById(sheetId);

    var stats = { trainsUpdated: 0, trainsAdded: 0, stationsUpserted: 0, scheduleUpserted: 0 };

    // 1) Station Sheet Upsert
    if (Array.isArray(data.stations) && data.stations.length) {
      stats.stationsUpserted = upsertStationsSheet_(ss.getSheetByName('Station'), data.stations);
    }

    // 2) Train Sheet Upsert (+ Comma-separated Train_Stops)
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

    // 3) Schedule Sheet Upsert
    if (Array.isArray(data.schedule) && data.schedule.length) {
      stats.scheduleUpserted = upsertScheduleSheet_(ss.getSheetByName('Schedule'), data.schedule);
    }

    return jsonResponse_({ success: true, stats: stats });
  } catch (err) {
    return jsonResponse_({ success: false, error: String(err && err.message ? err.message : err) });
  } finally {
    lock.releaseLock();
  }
}

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
  return !s || /^none$/i.test(s) || s === '--:--';
}

function blankRow_(len) {
  var a = [];
  for (var i = 0; i < len; i++) a.push('');
  return a;
}

function upsertStationsSheet_(sheet, stations) {
  if (!sheet || !stations.length) return 0;
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 7);
  var values = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol).getValues() : [];
  if (!values.length) {
    values = [['Station_Code', 'Station_Name', 'State', 'Zone', 'Address', 'Latitude', 'Longitude']];
    sheet.getRange(1, 1, 1, 7).setValues(values);
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
    var hasCoords = st.latitude != null && st.longitude != null && isFinite(Number(st.latitude)) && isFinite(Number(st.longitude));

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
  var values = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol).getValues() : [];
  if (!values.length) return { updated: 0, added: 0 };

  var hm = headerMap_(values[0]);
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
  for (var r = 1; r < values.length; r++) {
    var k = normTrainKey_(values[r][cNum]);
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
      var changed = false;
      if (t.trainName && (isEmptyOrNone_(row[cName]) || (!onlyInsertIfMissingOrFillBlank && String(row[cName]).trim() !== t.trainName))) {
        row[cName] = t.trainName; changed = true;
      }
      if (!onlyInsertIfMissingOrFillBlank) {
        if (t.fromCode && String(row[cFromC]).trim().toUpperCase() !== t.fromCode) { row[cFromC] = t.fromCode; changed = true; }
        if (t.fromName && String(row[cFromN]).trim() !== t.fromName) { row[cFromN] = t.fromName; changed = true; }
        if (t.departure && !isEmptyOrNone_(t.departure) && String(row[cDep]).trim() !== t.departure) { row[cDep] = t.departure; changed = true; }
        if (t.toCode && String(row[cToC]).trim().toUpperCase() !== t.toCode) { row[cToC] = t.toCode; changed = true; }
        if (t.toName && String(row[cToN]).trim() !== t.toName) { row[cToN] = t.toName; changed = true; }
        if (t.arrival && !isEmptyOrNone_(t.arrival) && String(row[cArr]).trim() !== t.arrival) { row[cArr] = t.arrival; changed = true; }
        if (t.distanceKm > 0 && !(Number(row[cDist]) > 0)) { row[cDist] = Number(t.distanceKm); changed = true; }
        if (t.routePointsCount > 0 && t.routePointsCount !== (Number(row[cPts]) || 0)) { row[cPts] = Number(t.routePointsCount); changed = true; }
      }
      var exStops = String(row[cStops] || '').trim();
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
      newRow[cDep] = t.departure || '';
      newRow[cToC] = t.toCode || '';
      newRow[cToN] = t.toName || '';
      newRow[cArr] = t.arrival || '';
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
  var values = lastRow >= 1 ? sheet.getRange(1, 1, lastRow, lastCol).getValues() : [];
  if (!values.length) {
    values = [['Schedule_ID', 'Train_Number', 'Train_Name', 'Station_Code', 'Station_Name', 'Arrival', 'Departure', 'Day']];
    sheet.getRange(1, 1, 1, 8).setValues(values);
  }

  var hm = headerMap_(values[0]);
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
  for (var r = 1; r < values.length; r++) {
    var k = normTrainKey_(values[r][cTrainNo]) + '|' + String(values[r][cStCode] || '').trim().toUpperCase();
    if (values[r][cTrainNo] && values[r][cStCode]) rowByKey[k] = r;
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

    if (rowIdx != null) {
      var row = values[rowIdx];
      var changed = false;
      if (s.trainName && String(row[cTrainName]).trim() !== s.trainName) { row[cTrainName] = s.trainName; changed = true; }
      if (s.stationName && String(row[cStName]).trim() !== s.stationName) { row[cStName] = s.stationName; changed = true; }
      if (!isEmptyOrNone_(s.arrival) && String(row[cArr]).trim() !== s.arrival) { row[cArr] = s.arrival; changed = true; }
      if (!isEmptyOrNone_(s.departure) && String(row[cDep]).trim() !== s.departure) { row[cDep] = s.departure; changed = true; }
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
      newRow[cArr] = !isEmptyOrNone_(s.arrival) ? s.arrival : 'None';
      newRow[cDep] = !isEmptyOrNone_(s.departure) ? s.departure : 'None';
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

function jsonResponse_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
