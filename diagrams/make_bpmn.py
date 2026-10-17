#!/usr/bin/env python3
"""Generates payroll-approval.bpmn: BPMN 2.0 with diagram interchange, four lanes.

Open the output in https://demo.bpmn.io or Camunda Modeler. Edit the NODES / FLOWS
tables below and re-run to regenerate:  python make_bpmn.py
"""

from pathlib import Path
from xml.sax.saxutils import quoteattr

POOL = dict(x=100, y=60, w=2440, h=630, name="Payroll approval with Imzo")

# id, name, top, height
LANES = [
    ("Lane_fin", "Finance manager", 60, 130),
    ("Lane_app", "Approver with Imzo", 190, 220),
    ("Lane_srv", "Approval service", 410, 150),
    ("Lane_core", "Core banking", 560, 130),
]

SIZE = {"start": (36, 36), "end": (36, 36), "user": (100, 80), "service": (100, 80), "gate": (50, 50)}

# id: (kind, name, lane id, centre x, centre y)
NODES = {
    "start": ("start", "Payroll due", "Lane_fin", 230, 125),
    "prepare": ("user", "Prepare payroll list", "Lane_fin", 340, 125),
    "submit": ("user", "Submit order", "Lane_fin", 480, 125),
    "show": ("user", "Show request QR", "Lane_fin", 900, 125),
    "scan_answer": ("user", "Scan answer QR", "Lane_fin", 1620, 125),
    "review": ("user", "Scan QR and review on Imzo", "Lane_app", 1060, 300),
    "g_match": ("gate", "Matches invoice?", "Lane_app", 1200, 300),
    "confirm": ("user", "Confirm with biometric", "Lane_app", 1340, 245),
    "reject": ("user", "Reject on Imzo", "Lane_app", 1340, 355),
    "answer": ("user", "Show signed answer QR", "Lane_app", 1480, 300),
    "store": ("service", "Store order and build request", "Lane_srv", 620, 470),
    "verify": ("service", "Verify signature and rules", "Lane_srv", 1760, 470),
    "g_valid": ("gate", "Approved and valid?", "Lane_srv", 1900, 470),
    "end_not": ("end", "Order not executed", "Lane_srv", 1900, 535),
    "g_all": ("gate", "All required approvals?", "Lane_srv", 2040, 470),
    "exec": ("service", "Execute stored order", "Lane_srv", 2200, 470),
    "post": ("service", "Post payments", "Lane_core", 2340, 625),
    "end_paid": ("end", "Payroll paid", "Lane_core", 2480, 625),
}


def box(n):
    kind, _, _, cx, cy = NODES[n]
    w, h = SIZE[kind]
    return cx - w // 2, cy - h // 2, w, h


def R(n):
    x, y, w, h = box(n)
    return (x + w, y + h // 2)


def L(n):
    x, y, w, h = box(n)
    return (x, y + h // 2)


def T(n):
    x, y, w, h = box(n)
    return (x + w // 2, y)


def B(n):
    x, y, w, h = box(n)
    return (x + w // 2, y + h)


# id, source, target, name, waypoints (None = straight line from right edge to left edge)
FLOWS = [
    ("f1", "start", "prepare", "", None),
    ("f2", "prepare", "submit", "", None),
    ("f3", "submit", "store", "", [R("submit"), (620, 125), T("store")]),
    ("f4", "store", "show", "", [R("store"), (800, 470), (800, 125), L("show")]),
    ("f5", "show", "review", "", [R("show"), (1060, 125), T("review")]),
    ("f6", "review", "g_match", "", None),
    ("f7", "g_match", "confirm", "yes", [T("g_match"), (1200, 245), L("confirm")]),
    ("f8", "g_match", "reject", "no", [B("g_match"), (1200, 355), L("reject")]),
    ("f9", "confirm", "answer", "", [R("confirm"), (1480, 245), T("answer")]),
    ("f10", "reject", "answer", "", [R("reject"), (1480, 355), B("answer")]),
    ("f11", "answer", "scan_answer", "", [R("answer"), (1545, 300), (1545, 125), L("scan_answer")]),
    ("f12", "scan_answer", "verify", "", [B("scan_answer"), (1620, 470), L("verify")]),
    ("f13", "verify", "g_valid", "", None),
    ("f14", "g_valid", "g_all", "yes", None),
    ("f15", "g_valid", "end_not", "no", [B("g_valid"), T("end_not")]),
    ("f16", "g_all", "exec", "yes", None),
    ("f17", "g_all", "show", "no: next approver", [T("g_all"), (2040, 178), (900, 178), B("show")]),
    ("f18", "exec", "post", "", [R("exec"), (2340, 470), T("post")]),
    ("f19", "post", "end_paid", "", None),
]

TAG = {"start": "startEvent", "end": "endEvent", "user": "userTask", "service": "serviceTask", "gate": "exclusiveGateway"}


def attr(**kw):
    return " ".join(f"{k}={quoteattr(str(v))}" for k, v in kw.items() if v != "")


def build() -> str:
    incoming = {n: [] for n in NODES}
    outgoing = {n: [] for n in NODES}
    for fid, src, dst, _, _ in FLOWS:
        outgoing[src].append(fid)
        incoming[dst].append(fid)

    out = []
    w = out.append
    w('<?xml version="1.0" encoding="UTF-8"?>')
    w('<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
      'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
      'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
      'xmlns:di="http://www.omg.org/spec/DD/20100524/DI" '
      'id="Definitions_imzo" targetNamespace="http://imzo.example/bpmn">')
    w('  <bpmn:collaboration id="Collaboration_1">')
    w(f'    <bpmn:participant {attr(id="Participant_1", name=POOL["name"], processRef="Process_1")} />')
    w('  </bpmn:collaboration>')
    w('  <bpmn:process id="Process_1" isExecutable="false">')
    w('    <bpmn:laneSet id="LaneSet_1">')
    for lid, lname, _, _ in LANES:
        w(f'      <bpmn:lane {attr(id=lid, name=lname)}>')
        for nid, (_, _, lane, _, _) in NODES.items():
            if lane == lid:
                w(f"        <bpmn:flowNodeRef>{nid}</bpmn:flowNodeRef>")
        w("      </bpmn:lane>")
    w("    </bpmn:laneSet>")
    for nid, (kind, name, _, _, _) in NODES.items():
        extra = ""
        w(f'    <bpmn:{TAG[kind]} {attr(id=nid, name=name)}{extra}>')
        for f in incoming[nid]:
            w(f"      <bpmn:incoming>{f}</bpmn:incoming>")
        for f in outgoing[nid]:
            w(f"      <bpmn:outgoing>{f}</bpmn:outgoing>")
        w(f"    </bpmn:{TAG[kind]}>")
    for fid, src, dst, name, _ in FLOWS:
        w(f'    <bpmn:sequenceFlow {attr(id=fid, name=name, sourceRef=src, targetRef=dst)} />')
    w("  </bpmn:process>")

    w("  <bpmndi:BPMNDiagram id=\"Diagram_1\">")
    w("    <bpmndi:BPMNPlane id=\"Plane_1\" bpmnElement=\"Collaboration_1\">")
    w(f'      <bpmndi:BPMNShape id="Participant_1_di" bpmnElement="Participant_1" isHorizontal="true">'
      f'<dc:Bounds x="{POOL["x"]}" y="{POOL["y"]}" width="{POOL["w"]}" height="{POOL["h"]}" /></bpmndi:BPMNShape>')
    for lid, _, top, height in LANES:
        w(f'      <bpmndi:BPMNShape id="{lid}_di" bpmnElement="{lid}" isHorizontal="true">'
          f'<dc:Bounds x="130" y="{top}" width="{POOL["w"] - 30}" height="{height}" /></bpmndi:BPMNShape>')
    for nid, (kind, _, _, _, _) in NODES.items():
        x, y, bw, bh = box(nid)
        marker = ' isMarkerVisible="true"' if kind == "gate" else ""
        w(f'      <bpmndi:BPMNShape id="{nid}_di" bpmnElement="{nid}"{marker}>'
          f'<dc:Bounds x="{x}" y="{y}" width="{bw}" height="{bh}" /></bpmndi:BPMNShape>')
    for fid, src, dst, _, pts in FLOWS:
        pts = pts or [R(src), L(dst)]
        wps = "".join(f'<di:waypoint x="{px}" y="{py}" />' for px, py in pts)
        w(f'      <bpmndi:BPMNEdge id="{fid}_di" bpmnElement="{fid}">{wps}</bpmndi:BPMNEdge>')
    w("    </bpmndi:BPMNPlane>")
    w("  </bpmndi:BPMNDiagram>")
    w("</bpmn:definitions>")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    target = Path(__file__).with_name("payroll-approval.bpmn")
    target.write_text(build(), encoding="utf-8")
    print(f"wrote {target}")
