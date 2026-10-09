# Field technician design contract

## Reference and purpose

The shared Android/iOS technician app follows `../web/DESIGN.md` and the reference
warehouse workflow. The main tasks are finding assigned work, photographing the
named evidence slots, recording exact material use, requesting stock and returning
unused stock. Attendance and payslips retain their separate existing modules.

## Tokens

Light: plane #f8f9fa, surface #ffffff, secondary surface #f3f2f1, text #1b1a19,
secondary text #605e5c, accent #0078d4, accent ink #ffffff, critical #a4262c.
Dark: plane #1b1a19, surface #201f1e, secondary surface #323130, text #ffffff,
secondary text #c8c6c4, accent #2899f5, accent ink #1b1a19, critical #ff99a4.
Use the system font at 14sp body, 12sp metadata, 18sp section and 24sp page title.
Spacing is 4/8/12/16/24dp; surfaces have 8dp radii, controls 4dp radii and minimum
48dp touch targets. Status always includes a text label.

## Navigation and scrolling

Use the StyleGallery scroll-body-shell pattern: the screen owns a bounded height;
the account/header and tab row stay visible, and one weighted body owns scrolling.
Source: https://github.com/changeroa/StyleGallery/blob/main/patterns/viewport-shell/scroll-body-shell.md
Five destinations: Ringkasan, Pekerjaan, Material, Permintaan, Retur. Compact
widths use an accessible horizontally scrolling tab row; only the selected screen
is rendered. Details have an explicit Kembali control. All content stacks and
wraps at phone widths, including long material names, serials and notes.

## Reusable components and actions

Panels contain one task and a clear section heading. Forms use visible labels
above editable controls, password masking, numerical quantity keyboards and
inline errors. Selectors search and paginate names, retain selected values, and
never interpret the first page as the entire directory. Review panels name the
material, quantity, destination, evidence slot and affected work order.

Requests, returns, evidence and completion are saved in the encrypted account,
tenant, device and session scoped queue before delivery. Queued drafts, uncertain
delivery, rejection and conflict are distinct visible states. A pending command
locks its review; retries use the exact original body, bytes and key. A queued
draft can be discarded; an attempted command must first resolve its outcome.
Completion refreshes assignment, revision, current photos and own stock. Immediate
warehouse handover appears as Material Saya without an extra acknowledgment.

## Accessibility and verification

System light/dark theme and font scaling apply. All controls have accessible
names; tab selection and operation states are announced through semantics.
Camera/picker starts only from the named photo action. No motion is required.
Offline snapshots are explicitly labeled; errors retain drafts and offer retry.
No accepted accessibility debt. Android emulator and iOS compilation evidence
must be recorded in docs/mobile.md; source review alone is not runtime proof.
