// The bean graph view of ReStudio Spring. It draws the graph as SVG with a layered layout.
// The plugin loads this file into a JCEF browser. It uses no network and no other library.
(function () {
  'use strict';

  var NS = 'http://www.w3.org/2000/svg';
  var NODE_W = 184, NODE_H = 42, GAP_X = 26, GAP_Y = 84, SUB_ROW_GAP = 26, MAX_ROW = 12;
  var L = window.beanGraphLabels || {};

  var svg = document.getElementById('canvas');
  var viewport = document.getElementById('viewport');
  var edgeLayer = document.getElementById('edges');
  var nodeLayer = document.getElementById('nodes');
  var statusEl = document.getElementById('status');
  var emptyEl = document.getElementById('empty');

  var graph = { nodes: [], edges: [] };
  var byId = new Map();
  var outEdges = new Map();
  var inEdges = new Map();
  var positions = new Map();
  var nodeEls = new Map();
  var edgeEls = new Map();
  var view = { x: 0, y: 0, k: 1 };
  var selected = null;
  var query = '';
  var matches = [];
  var matchIndex = -1;

  function send(message) {
    if (typeof window.beanGraphSend === 'function') window.beanGraphSend(message);
  }

  function label(key, args) {
    var text = L[key] || key;
    (args || []).forEach(function (value, index) { text = text.split('{' + index + '}').join(String(value)); });
    return text;
  }

  function el(name, attributes, parent) {
    var node = document.createElementNS(NS, name);
    for (var key in attributes) node.setAttribute(key, attributes[key]);
    if (parent) parent.appendChild(node);
    return node;
  }

  // Layout

  function layout(nodes, edges) {
    var ids = nodes.map(function (n) { return n.id; });
    var succ = new Map(), pred = new Map();
    ids.forEach(function (id) { succ.set(id, []); pred.set(id, []); });
    edges.forEach(function (e) {
      if (e.from === e.to || !succ.has(e.from) || !succ.has(e.to)) return;
      succ.get(e.from).push(e.to);
      pred.get(e.to).push(e.from);
    });
    var connected = ids.filter(function (id) { return succ.get(id).length || pred.get(id).length; });
    var isolated = ids.filter(function (id) { return !(succ.get(id).length || pred.get(id).length); });

    // Remove the cycles: reverse the back edges of a depth-first search.
    var state = new Map(), reversed = new Set();
    connected.forEach(function (root) {
      if (state.has(root)) return;
      var stack = [[root, 0]];
      state.set(root, 1);
      while (stack.length) {
        var top = stack[stack.length - 1];
        var list = succ.get(top[0]);
        if (top[1] < list.length) {
          var next = list[top[1]++];
          var s = state.get(next);
          if (s === 1) reversed.add(top[0] + '\n' + next);
          else if (!s) { state.set(next, 1); stack.push([next, 0]); }
        } else {
          state.set(top[0], 2);
          stack.pop();
        }
      }
    });
    var dsucc = new Map(), dpred = new Map();
    connected.forEach(function (id) { dsucc.set(id, new Set()); dpred.set(id, new Set()); });
    connected.forEach(function (u) {
      succ.get(u).forEach(function (v) {
        var a = u, b = v;
        if (reversed.has(u + '\n' + v)) { a = v; b = u; }
        dsucc.get(a).add(b);
        dpred.get(b).add(a);
      });
    });

    // Layers: the longest path from the beans that no bean depends on.
    var indegree = new Map(), layerOf = new Map(), order = [];
    connected.forEach(function (id) { indegree.set(id, dpred.get(id).size); layerOf.set(id, 0); });
    var queue = connected.filter(function (id) { return indegree.get(id) === 0; });
    while (queue.length) {
      var id = queue.shift();
      order.push(id);
      dsucc.get(id).forEach(function (s) {
        layerOf.set(s, Math.max(layerOf.get(s), layerOf.get(id) + 1));
        indegree.set(s, indegree.get(s) - 1);
        if (indegree.get(s) === 0) queue.push(s);
      });
    }
    var layers = [];
    order.forEach(function (id) {
      var l = layerOf.get(id);
      (layers[l] = layers[l] || []).push(id);
    });

    // Order in the layers: barycenter sweeps.
    var rank = new Map();
    function updateRanks(layer) { layer.forEach(function (id, i) { rank.set(id, layer.length > 1 ? i / (layer.length - 1) : 0.5); }); }
    layers.forEach(updateRanks);
    function sweep(layer, neighbors) {
      var bary = new Map();
      layer.forEach(function (id) {
        var sum = 0, count = 0;
        neighbors.get(id).forEach(function (n) { sum += rank.get(n); count++; });
        bary.set(id, count ? sum / count : rank.get(id));
      });
      layer.sort(function (a, b) { return bary.get(a) - bary.get(b); });
      updateRanks(layer);
    }
    for (var iteration = 0; iteration < 6; iteration++) {
      for (var i = 1; i < layers.length; i++) sweep(layers[i], dpred);
      for (var j = layers.length - 2; j >= 0; j--) sweep(layers[j], dsucc);
    }

    // Coordinates. A wide layer goes into more than one row. A deep graph goes into more than one band of layers,
    // from left to right, so that the graph is not much higher than wide.
    var result = new Map(), y = 0, widest = 0;
    function row(list, top, left) {
      var width = list.length * (NODE_W + GAP_X) - GAP_X;
      widest = Math.max(widest, width);
      list.forEach(function (id, index) {
        result.set(id, { x: left - width / 2 + index * (NODE_W + GAP_X) + NODE_W / 2, y: top + NODE_H / 2 });
      });
    }
    var rows = [];
    layers.forEach(function (layer) {
      for (var start = 0; start < layer.length; start += MAX_ROW) {
        rows.push({ ids: layer.slice(start, start + MAX_ROW), gap: start + MAX_ROW < layer.length ? SUB_ROW_GAP : GAP_Y });
      }
    });
    var rowWidth = function (r) { return r.ids.length * (NODE_W + GAP_X) - GAP_X; };
    var totalHeight = rows.reduce(function (sum, r) { return sum + NODE_H + r.gap; }, 0);
    var maxWidth = rows.reduce(function (m, r) { return Math.max(m, rowWidth(r)); }, 0);
    var bands = Math.max(1, Math.min(8, Math.round(Math.sqrt(totalHeight / Math.max(maxWidth, NODE_W) / 1.5))));
    var bandHeight = totalHeight / bands, left = 0, bandStart = 0;
    while (bandStart < rows.length) {
      var bandRows = [], height = 0, bandWidth = 0;
      while (bandStart < rows.length && (height < bandHeight || !bandRows.length)) {
        var r = rows[bandStart++];
        bandRows.push(r);
        height += NODE_H + r.gap;
        bandWidth = Math.max(bandWidth, rowWidth(r));
      }
      var top = 0;
      bandRows.forEach(function (r) { row(r.ids, top, left + bandWidth / 2); top += NODE_H + r.gap; });
      y = Math.max(y, top);
      left += bandWidth + GAP_Y * 2;
    }
    var allWidth = left - GAP_Y * 2;
    result.forEach(function (p) { p.x -= allWidth / 2; });
    if (isolated.length) {
      isolated.sort(function (a, b) { return byId.get(a).name.localeCompare(byId.get(b).name); });
      var columns = Math.max(MAX_ROW, Math.ceil(Math.sqrt(isolated.length)));
      for (var k = 0; k < isolated.length; k += columns) {
        row(isolated.slice(k, k + columns), y, 0);
        y += NODE_H + SUB_ROW_GAP;
      }
    }
    return result;
  }

  // Rendering

  function truncate(text, max) {
    return text.length > max ? text.substring(0, max - 1) + '…' : text;
  }

  function nodeTitle(node) {
    var lines = [node.name, node.qualifiedType, label('kind.' + node.kind)];
    if (node.profiles.length) lines.push(label('tooltip.profiles', [node.profiles.join(', ')]));
    if (node.primary) lines.push(label('tooltip.primary'));
    if (node.conditional) lines.push(label('tooltip.conditional'));
    if (node.library) lines.push(label('tooltip.library'));
    if (node.file) lines.push(node.file);
    return lines.join('\n');
  }

  function edgeTitle(edge) {
    var from = byId.get(edge.from), to = byId.get(edge.to);
    var lines = [from.name + ' → ' + to.name, label('status.' + (edge.kind === 'FACTORY' ? 'FACTORY' : edge.status))];
    if (edge.points.length) lines.push(label('tooltip.points', [edge.points.join(', ')]));
    return lines.join('\n');
  }

  function edgePath(edge) {
    var s = positions.get(edge.from), t = positions.get(edge.to);
    var down = t.y >= s.y + NODE_H / 2;
    var up = t.y <= s.y - NODE_H / 2;
    var sx = s.x, tx = t.x, sy, ty, c;
    if (down || up) {
      sy = s.y + (down ? NODE_H / 2 : -NODE_H / 2);
      ty = t.y + (down ? -NODE_H / 2 : NODE_H / 2);
      c = Math.max(24, Math.abs(ty - sy) / 2) * (down ? 1 : -1);
      return 'M' + sx + ',' + sy + ' C' + sx + ',' + (sy + c) + ' ' + tx + ',' + (ty - c) + ' ' + tx + ',' + ty;
    }
    // The same row: go below the nodes.
    sy = s.y + NODE_H / 2;
    ty = t.y + NODE_H / 2;
    c = 40;
    return 'M' + sx + ',' + sy + ' C' + sx + ',' + (sy + c) + ' ' + tx + ',' + (ty + c) + ' ' + tx + ',' + ty;
  }

  function render() {
    edgeLayer.textContent = '';
    nodeLayer.textContent = '';
    nodeEls.clear();
    edgeEls.clear();
    graph.edges.forEach(function (edge) {
      if (!positions.has(edge.from) || !positions.has(edge.to)) return;
      var status = edge.kind === 'FACTORY' ? 'FACTORY' : edge.status;
      var group = el('g', { 'class': 'edge status-' + status }, edgeLayer);
      var d = edgePath(edge);
      el('path', { d: d, 'class': 'hit' }, group);
      el('path', { d: d, 'class': 'line', 'marker-end': 'url(#arrow-' + status + ')' }, group);
      el('title', {}, group).textContent = edgeTitle(edge);
      group.addEventListener('click', function (event) {
        event.stopPropagation();
        send('navigate:' + edge.id);
      });
      edgeEls.set(edge.id, group);
    });
    graph.nodes.forEach(function (node) {
      var p = positions.get(node.id);
      var classes = ['node', 'kind-' + node.kind];
      if (node.library) classes.push('library');
      if (node.neighbor) classes.push('neighbor');
      if (node.primary) classes.push('primary');
      if (node.conditional) classes.push('conditional');
      var group = el('g', {
        'class': classes.join(' '),
        transform: 'translate(' + (p.x - NODE_W / 2) + ',' + (p.y - NODE_H / 2) + ')'
      }, nodeLayer);
      el('rect', { 'class': 'box', width: NODE_W, height: NODE_H, rx: 6, ry: 6 }, group);
      el('rect', { 'class': 'stripe', width: 5, height: NODE_H - 8, x: 4, y: 4, rx: 2, ry: 2 }, group);
      var prefix = node.primary ? '★ ' : '';
      el('text', { 'class': 'name', x: 16, y: 18 }, group).textContent = truncate(prefix + node.name, 24);
      el('text', { 'class': 'type', x: 16, y: 33 }, group).textContent = truncate(node.type, 28);
      el('title', {}, group).textContent = nodeTitle(node);
      group.addEventListener('click', function (event) {
        event.stopPropagation();
        select(node.id);
        send('navigate:' + node.id);
      });
      nodeEls.set(node.id, group);
    });
  }

  // Selection and highlight

  function closure(start, edgesOf, key) {
    var seen = new Set(), stack = [start];
    while (stack.length) {
      var id = stack.pop();
      (edgesOf.get(id) || []).forEach(function (edge) {
        var next = edge[key];
        if (!seen.has(next) && next !== start) { seen.add(next); stack.push(next); }
      });
    }
    return seen;
  }

  function applyHighlight() {
    var active = selected && byId.has(selected);
    var dependencies = active ? closure(selected, outEdges, 'to') : new Set();
    var dependents = active ? closure(selected, inEdges, 'from') : new Set();
    var matchSet = new Set(matches);
    nodeEls.forEach(function (group, id) {
      var list = group.classList;
      list.toggle('selected', active && id === selected);
      list.toggle('dependency', dependencies.has(id));
      list.toggle('dependent', dependents.has(id) && !dependencies.has(id));
      list.toggle('faded', !!active && id !== selected && !dependencies.has(id) && !dependents.has(id));
      list.toggle('match', matchSet.has(id));
    });
    graph.edges.forEach(function (edge) {
      var group = edgeEls.get(edge.id);
      if (!group) return;
      var dep = active && (edge.from === selected || dependencies.has(edge.from)) && dependencies.has(edge.to);
      var dnt = active && (edge.to === selected || dependents.has(edge.to)) && dependents.has(edge.from);
      group.classList.toggle('dependency', !!dep);
      group.classList.toggle('dependent', !!dnt && !dep);
      group.classList.toggle('faded', !!active && !dep && !dnt);
    });
    updateStatus(active ? [dependencies.size, dependents.size] : null);
  }

  function select(id) {
    selected = id;
    applyHighlight();
  }

  function updateStatus(selection) {
    var text = label('status.count', [graph.nodes.length, graph.edges.length]);
    if (selection) text += ' · ' + label('status.selection', [byId.get(selected).name, selection[0], selection[1]]);
    if (query) text += ' · ' + label('status.matches', [matches.length]);
    statusEl.textContent = text;
  }

  // View

  function applyView() {
    viewport.setAttribute('transform', 'translate(' + view.x + ',' + view.y + ') scale(' + view.k + ')');
  }

  function bounds() {
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    positions.forEach(function (p) {
      minX = Math.min(minX, p.x - NODE_W / 2); maxX = Math.max(maxX, p.x + NODE_W / 2);
      minY = Math.min(minY, p.y - NODE_H / 2); maxY = Math.max(maxY, p.y + NODE_H / 2);
    });
    return minX === Infinity ? null : { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
  }

  function fit() {
    var b = bounds();
    var w = svg.clientWidth || window.innerWidth, h = svg.clientHeight || window.innerHeight;
    if (!b) { view = { x: w / 2, y: h / 2, k: 1 }; applyView(); return; }
    var k = Math.min(1.2, Math.max(0.05, Math.min((w - 40) / b.w, (h - 60) / b.h)));
    view = { k: k, x: w / 2 - (b.x + b.w / 2) * k, y: h / 2 - (b.y + b.h / 2) * k };
    applyView();
  }

  function center(id) {
    var p = positions.get(id);
    if (!p) return;
    var w = svg.clientWidth || window.innerWidth, h = svg.clientHeight || window.innerHeight;
    view.k = Math.max(view.k, 0.8);
    view.x = w / 2 - p.x * view.k;
    view.y = h / 2 - p.y * view.k;
    applyView();
  }

  function zoom(factor, cx, cy) {
    var w = svg.clientWidth || window.innerWidth, h = svg.clientHeight || window.innerHeight;
    if (cx === undefined) { cx = w / 2; cy = h / 2; }
    var k = Math.min(4, Math.max(0.05, view.k * factor));
    view.x = cx - (cx - view.x) * (k / view.k);
    view.y = cy - (cy - view.y) * (k / view.k);
    view.k = k;
    applyView();
  }

  svg.addEventListener('wheel', function (event) {
    event.preventDefault();
    var rect = svg.getBoundingClientRect();
    zoom(Math.exp(-event.deltaY * 0.0015), event.clientX - rect.left, event.clientY - rect.top);
  }, { passive: false });

  var drag = null;
  svg.addEventListener('pointerdown', function (event) {
    if (event.button !== 0) return;
    drag = { x: event.clientX, y: event.clientY, vx: view.x, vy: view.y, moved: false, onNode: !!event.target.closest('.node, .edge') };
  });
  window.addEventListener('pointermove', function (event) {
    if (!drag || drag.onNode) return;
    var dx = event.clientX - drag.x, dy = event.clientY - drag.y;
    if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 4) return;
    drag.moved = true;
    svg.classList.add('panning');
    view.x = drag.vx + dx;
    view.y = drag.vy + dy;
    applyView();
  });
  window.addEventListener('pointerup', function () {
    if (drag && !drag.moved && !drag.onNode && selected) select(null);
    drag = null;
    svg.classList.remove('panning');
  });
  window.addEventListener('keydown', function (event) {
    if (event.key === '+' || event.key === '=') zoom(1.25);
    else if (event.key === '-') zoom(0.8);
    else if (event.key === '0') fit();
    else if (event.key === 'Escape') select(null);
  });

  // Search

  function search(text, focus) {
    query = (text || '').trim().toLowerCase();
    matches = !query ? [] : graph.nodes.filter(function (node) {
      return node.name.toLowerCase().indexOf(query) >= 0 || node.type.toLowerCase().indexOf(query) >= 0;
    }).map(function (node) { return node.id; });
    matchIndex = matches.length ? 0 : -1;
    applyHighlight();
    if (focus !== false && matchIndex >= 0) center(matches[0]);
    return matches.length;
  }

  function searchNext() {
    if (!matches.length) return;
    matchIndex = (matchIndex + 1) % matches.length;
    center(matches[matchIndex]);
  }

  // API for the plugin

  function setData(data, keepView) {
    graph = data;
    byId = new Map();
    outEdges = new Map();
    inEdges = new Map();
    graph.nodes.forEach(function (node) { byId.set(node.id, node); outEdges.set(node.id, []); inEdges.set(node.id, []); });
    graph.edges.forEach(function (edge) {
      if (!byId.has(edge.from) || !byId.has(edge.to)) return;
      outEdges.get(edge.from).push(edge);
      inEdges.get(edge.to).push(edge);
    });
    positions = layout(graph.nodes, graph.edges);
    render();
    emptyEl.style.display = graph.nodes.length ? 'none' : 'block';
    if (selected && !byId.has(selected)) selected = null;
    search(query, false);
    if (!keepView) fit();
  }

  function setTheme(variables) {
    for (var key in variables) document.documentElement.style.setProperty('--' + key, variables[key]);
  }

  function reveal(ids) {
    var known = ids.filter(function (id) { return byId.has(id); });
    if (!known.length) return false;
    select(known[0]);
    center(known[0]);
    return true;
  }

  window.beanGraph = {
    setData: setData,
    setTheme: setTheme,
    search: search,
    searchNext: searchNext,
    reveal: reveal,
    select: select,
    fit: fit,
    zoom: zoom,
    // For tests and debugging: the computed node positions.
    positions: function () { var o = {}; positions.forEach(function (p, id) { o[id] = p; }); return o; }
  };
  window.addEventListener('resize', function () { if (!graph.nodes.length) fit(); });
  send('ready');
})();
