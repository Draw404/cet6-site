/* 背词 · 纯前端核心逻辑（与服务器版 App.java 的排期/重练规则保持一致）
 * 无 DOM 依赖：浏览器里挂到 window.VocabLogic，Node 里可直接 require 测试。 */
(function (global) {
  'use strict';

  // 艾宾浩斯复习节点：学会后第 1、2、4、7、15、30 天各复习一次，共 6 轮
  var OFFSETS = [1, 2, 4, 7, 15, 30];
  // 当天答错后，需要在当天连续点对几次「认识」才算今日过关（记录仍为不认识）
  var INDAY_NEED = 3;

  function pad(n) { return String(n).padStart(2, '0'); }

  // 本地日期 yyyy-MM-dd（按使用者设备时区）
  function todayStr(d) {
    d = d || new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function addDays(dateStr, n) {
    var p = dateStr.split('-').map(Number);
    var d = new Date(p[0], p[1] - 1, p[2] + n);
    return todayStr(d);
  }

  function daysBetween(a, b) { // b - a（天）
    var pa = a.split('-').map(Number), pb = b.split('-').map(Number);
    var da = Date.UTC(pa[0], pa[1] - 1, pa[2]), db = Date.UTC(pb[0], pb[1] - 1, pb[2]);
    return Math.round((db - da) / 86400000);
  }

  function newWord(fields, today) {
    return {
      word: (fields.word || '').trim(),
      phonetic: fields.phonetic || '',
      pos: fields.pos || '',
      meaning: fields.meaning || '',
      sentence: fields.sentence || '',
      sentenceCn: fields.sentenceCn || '',
      source: fields.source || '',
      added: fields.added || today,
      learned: null,   // 首次学会日期，未学为 null
      due: null,       // 下次复习日期，未排期/已毕业为 null
      stage: 0,        // 已完成的复习轮数 0..6
      lapses: 0,       // 首答点过「不认识」的次数
      day: null,       // 最近一次「当天首答」日期
      dayResult: '',   // 当天首答结果："pass" / "fail"
      dayStreak: 0,    // 当天重练中已连续点对的次数
      dayDone: false   // 今天是否已完成
    };
  }

  // 老数据/导入数据补齐缺失字段，保证后续逻辑不判空
  function normalize(w, today) {
    var base = newWord(w, today);
    for (var k in base) {
      if (w[k] === undefined || w[k] === null) {
        if (k !== 'learned' && k !== 'due' && k !== 'day') w[k] = base[k];
      }
    }
    if (w.learned === undefined) w.learned = null;
    if (w.due === undefined) w.due = null;
    if (w.day === undefined) w.day = null;
    return w;
  }

  function findWord(words, word) {
    if (!word) return null;
    var t = word.trim().toLowerCase();
    for (var i = 0; i < words.length; i++) {
      if (words[i].word.toLowerCase() === t) return words[i];
    }
    return null;
  }

  // 艾宾浩斯排期（当天首答时调用，决定记录与下次日期）
  function applyReview(w, known, today) {
    if (w.learned === null) {
      // 首次学习：从今天起锚定，明天进入第 1 轮复习
      w.learned = today;
      w.stage = 0;
      w.due = addDays(today, OFFSETS[0]);
      if (!known) w.lapses++;
      return;
    }
    if (!known) {
      // 忘了：打回起点，以今天为新的锚点重走阶梯
      w.lapses++;
      w.learned = today;
      w.stage = 0;
      w.due = addDays(today, OFFSETS[0]);
      return;
    }
    w.stage++;
    if (w.stage >= OFFSETS.length) {
      w.due = null; // 6 轮全过，毕业
    } else {
      var next = addDays(w.learned, OFFSETS[w.stage]);
      if (next <= today) next = addDays(today, 1); // 逾期复习：下一档钳制为次日
      w.due = next;
    }
  }

  // 是否正处在「当天重练未过关」状态：首答在今天、首答不认识、还没连对达标
  function isDrilling(w, today) {
    return w.day === today && w.dayResult === 'fail' && !w.dayDone;
  }

  /* 处理一次作答，返回 {first, done, streak, need}
   * 当天首答决定记录与排期；首答不认识后进入重练，重练作答只动连对计数，
   * 不改排期、不加 lapses，连对达 INDAY_NEED 次过关。 */
  function handleReview(w, known, today) {
    var r;
    if (w.day !== today) {
      applyReview(w, known, today);
      w.day = today;
      w.dayResult = known ? 'pass' : 'fail';
      w.dayStreak = 0;
      w.dayDone = known;
      r = { first: true, done: known, streak: 0, need: INDAY_NEED };
    } else if (isDrilling(w, today)) {
      if (known) {
        w.dayStreak++;
        if (w.dayStreak >= INDAY_NEED) w.dayDone = true;
      } else {
        w.dayStreak = 0;
      }
      r = { first: false, done: w.dayDone, streak: w.dayStreak, need: INDAY_NEED };
    } else {
      r = { first: false, done: true, streak: w.dayStreak, need: INDAY_NEED };
    }
    return r;
  }

  function counts(words, today) {
    var c = { total: words.length, fresh: 0, due: 0, learning: 0, graduated: 0 };
    words.forEach(function (w) {
      if (w.learned === null) {
        if (w.added <= today) c.fresh++;
      } else if (w.due === null) {
        c.graduated++;
      } else {
        c.learning++;
        if (w.due <= today || isDrilling(w, today)) c.due++;
      }
    });
    return c;
  }

  // 今日队列：到期复习（含重练中的词）在前，新词在后
  function buildToday(words, today) {
    var due = [], fresh = [];
    words.forEach(function (w) {
      if (w.learned === null) {
        if (w.added <= today) fresh.push(w);
      } else if (w.due !== null && (w.due <= today || isDrilling(w, today))) {
        due.push(w);
      }
    });
    return { due: due, fresh: fresh, counts: counts(words, today) };
  }

  // 合并导入：只收新词（按词名不区分大小写判重），返回新增个数
  function mergeWords(existing, incoming, today) {
    var added = 0;
    incoming.forEach(function (raw) {
      if (!raw || !raw.word || !String(raw.word).trim()) return;
      if (findWord(existing, raw.word)) return;
      existing.push(normalize(newWord(raw, today), today));
      added++;
    });
    return added;
  }

  var api = {
    OFFSETS: OFFSETS, INDAY_NEED: INDAY_NEED,
    todayStr: todayStr, addDays: addDays, daysBetween: daysBetween,
    newWord: newWord, normalize: normalize, findWord: findWord,
    applyReview: applyReview, isDrilling: isDrilling,
    handleReview: handleReview, counts: counts, buildToday: buildToday,
    mergeWords: mergeWords
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.VocabLogic = api;
})(typeof window !== 'undefined' ? window : globalThis);
