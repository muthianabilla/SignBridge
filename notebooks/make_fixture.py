"""
Run this as an extra cell in notebook 03, after clip_features is defined.

It dumps one real clip's raw landmarks and the features Python produces for it.
Joachim feeds the same landmarks through SignPreprocessor.kt on the phone and
compares. If the two disagree, the model will underperform on device with no
error message anywhere, so this check is worth the ten minutes it takes.
"""
import json
import numpy as np
import pandas as pd

CLIP = sub[sub['sign'] == 'hello']['path'].iloc[0]      # any clip will do
df = pd.read_parquet(f'{DATA}/{CLIP}',
                     columns=['frame', 'type', 'landmark_index', 'x', 'y'])
frames = np.sort(df['frame'].unique())

def pick(kind, idxs):
    """-> list of [x, y] per frame, null where the detector found nothing"""
    sub_df = df[df['type'] == kind]
    out = []
    for f in frames:
        g = sub_df[sub_df['frame'] == f].set_index('landmark_index')
        row = []
        for i in idxs:
            if i in g.index:
                x, y = float(g.loc[i, 'x']), float(g.loc[i, 'y'])
                row.append(None if (x != x or y != y) else [x, y])
            else:
                row.append(None)
        out.append(row)
    return out

fixture = {
    'clip': CLIP,
    'n_frames': int(len(frames)),
    'left_hand':  pick('left_hand',  list(range(21))),
    'right_hand': pick('right_hand', list(range(21))),
    'shoulders':  pick('pose', [POSE_LSH, POSE_RSH]),
    'face':       pick('face', [FACE_FOREHEAD, FACE_NOSE, FACE_CHIN]),
    'expected':   clip_features(CLIP).reshape(-1).tolist(),
}
with open('/kaggle/working/parity_fixture.json', 'w') as fh:
    json.dump(fixture, fh)

print('clip      :', CLIP)
print('frames    :', len(frames))
print('expected  :', len(fixture['expected']), 'floats =', T, 'x', FEAT)
print('first 8   :', [round(v, 5) for v in fixture['expected'][:8]])
print('\nsaved /kaggle/working/parity_fixture.json')
