"""Terminal walkthrough of the on-stage demo. Run: python demo.py"""

import os

import imzo_ref as imzo

PAYROLL = [
    ["Aziza Karimova", "86000000000000000001", 450_000_000],
    ["Bobur Tursunov", "86000000000000000002", 520_000_000],
    ["Dilnoza Yusupova", "86000000000000000003", 380_050_000],
]


def soum(tiyin: int) -> str:
    return f"{tiyin / 100:,.2f} UZS"


def show(review: imzo.Review) -> None:
    print(f"   phone shows: {review.op} | {review.ref} | {review.count} items | total {soum(review.total)}")
    for name, account, amt in review.items:
        print(f"     {name:<20} {account}  {soum(amt)}")
    print(f"   fingerprint: {review.fingerprint}")


def run(title: str, items, tampered_items=None, approve=True) -> None:
    print(f"\n=== {title} ===")
    server = imzo.Server()
    approver = imzo.Device()
    server.register_device(approver.public_key, "alice")

    # What the finance manager sees on the laptop is `items`.
    # What reaches the server is `tampered_items` when malware is active.
    submitted = tampered_items or items
    requests = server.create_order(
        order_id="payroll-oct", maker="mike", op="payroll", ref="Payroll October", items=submitted, approvers=[approver.id]
    )
    request = requests[approver.id]
    frames = imzo.frames_encode("rq", request, chunk=200)
    print(f"1. laptop shows the order it was given, and plays {len(frames)} QR frames to the phone")

    _, received = imzo.frames_decode(frames)
    review = approver.review(received)
    print("2. phone scans, parses strictly, recomputes the total:")
    show(review)

    answer = approver.respond(review, approve)
    print(f"3. approver {'confirms with fingerprint' if approve else 'rejects'}; phone shows {len(imzo.frames_encode('rs', answer))} answer frames")

    status = server.submit_response(answer)
    print(f"4. server verifies signature and rules -> order {status}")
    print(f"   executed by the core: {[(e['order'], len(e['items'])) for e in server.executed] or 'nothing'}")
    print(f"   audit chain valid: {server.verify_audit()} ({len(server.audit)} events)")


if __name__ == "__main__":
    run("Normal run", PAYROLL)
    swapped = [PAYROLL[0], ["Bobur Tursunov", "86000000000000000099", 520_000_000], PAYROLL[2]]
    run("Attack run: malware swaps one beneficiary; approver compares with the printed list and rejects", PAYROLL, swapped, approve=False)
