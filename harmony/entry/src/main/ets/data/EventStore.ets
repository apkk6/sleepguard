import { relationalStore } from '@kit.ArkData';
import { Context } from '@kit.AbilityKit';

/** 睡眠事件（字段与 Android 端 EventStore 一致） */
export class SleepEvent {
  id: number = 0
  type: string = ''        // 'SNORE' | 'APNEA'
  startEpochMs: number = 0
  durationMs: number = 0
  wavPath: string = ''
  score: number = 0
}

export class EventStore {
  private store: relationalStore.RdbStore | null = null

  async init(ctx: Context): Promise<void> {
    const cfg: relationalStore.StoreConfig = {
      name: 'sleepguard.db',
      securityLevel: relationalStore.SecurityLevel.S1
    }
    this.store = await relationalStore.getRdbStore(ctx, cfg)
    await this.store.executeSql(
      'CREATE TABLE IF NOT EXISTS events(' +
      'id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, startEpochMs INTEGER, ' +
      'durationMs INTEGER, wavPath TEXT, score REAL)'
    )
  }

  async insert(type: string, startMs: number, durMs: number, path: string, score: number): Promise<void> {
    if (!this.store) {
      return
    }
    const bucket: relationalStore.ValuesBucket = {
      'type': type, 'startEpochMs': startMs,
      'durationMs': durMs, 'wavPath': path, 'score': score
    }
    await this.store.insert('events', bucket)
  }

  async querySince(dayStartMs: number): Promise<SleepEvent[]> {
    const out: SleepEvent[] = []
    if (!this.store) {
      return out
    }
    const pred = new relationalStore.RdbPredicates('events')
    pred.greaterThan('startEpochMs', dayStartMs).orderByDesc('startEpochMs')
    const rs = await this.store.query(pred, ['id', 'type', 'startEpochMs', 'durationMs', 'wavPath', 'score'])
    while (rs.goToNextRow()) {
      const e = new SleepEvent()
      e.id = rs.getLong(rs.getColumnIndex('id'))
      e.type = rs.getString(rs.getColumnIndex('type'))
      e.startEpochMs = rs.getLong(rs.getColumnIndex('startEpochMs'))
      e.durationMs = rs.getLong(rs.getColumnIndex('durationMs'))
      e.wavPath = rs.getString(rs.getColumnIndex('wavPath'))
      e.score = rs.getDouble(rs.getColumnIndex('score'))
      out.push(e)
    }
    rs.close()
    return out
  }
}
