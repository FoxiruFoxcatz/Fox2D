#!/usr/bin/env python3
"""Hooks KeyGraphUI.kt into the project: python3 patch_keygraph.py <ToolRail.kt> <EditorScreen.kt>
Anchor-asserted, idempotent; also migrates the first version's Dock hook. Edits in place (keep a copy / commit first)."""
import sys

def patch(path, edits):
    s = open(path, encoding='utf-8').read()
    for name, old, new in edits:
        if new and new in s and old != new:
            print(f'[skip] {name} already applied'); continue
        n = s.count(old)
        if n == 0 and new == '':
            print(f'[skip] {name} not present'); continue
        assert n == 1, f'{path}: anchor for "{name}" found {n}x'
        s = s.replace(old, new)
        print(f'[ok]   {name}')
    open(path, 'w', encoding='utf-8').write(s)

rail, editor = sys.argv[1], sys.argv[2]

patch(rail, [(
    'rail button',
    '''            ToolBtn(Ico.Layers, "Layers", state.panel == Panel.Layers, RailBtn) {''',
    '''            KeyGraphToolBtn(state, RailBtn)   // KeyGraphUI.kt: graph / keyframes editor
            ToolBtn(Ico.Layers, "Layers", state.panel == Panel.Layers, RailBtn) {''',
)])

OLD_PANEL = '''            // KeyGraph Editor (KeyGraphUI.kt) replaces transport + timeline while it is open
            AnimatedVisibility(state.keyGraph.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { KeyGraphPanel(state) }
'''
patch(editor, [
    ('dock: remove v1 panel hook', OLD_PANEL, ''),
    ('dock: panel above Transform / rig / timeline',
     '''        // Transform popup takes the place of the player controls + timeline
''',
     '''        // KeyGraph Editor (KeyGraphUI.kt): sits above the Transform popup / rig panel so its sliders stay usable; replaces transport + timeline
        AnimatedVisibility(state.keyGraph.open && state.placing == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { KeyGraphPanel(state) }
        // Transform popup takes the place of the player controls + timeline
'''),
    ('dock: hide transport + timeline',
     '''            AnimatedVisibility(state.rigPosedView, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(''',
     '''            AnimatedVisibility(state.rigPosedView && !state.keyGraph.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row('''),
])
