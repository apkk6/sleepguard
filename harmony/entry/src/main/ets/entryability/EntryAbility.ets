import { AbilityConstantLaunchParam, UIAbility, Want } from '@kit.AbilityKit';
import { abilityAccessCtrl, Permissions, Context } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { EventStore } from '../data/EventStore';

const TAG = 'EntryAbility';

export class AbilityShare {
  static store: EventStore | null = null
  static ctx: Context | null = null
}

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstantLaunchParam): void {
    hilog.info(0x0000, TAG, 'onCreate');
  }

  onDestroy(): void {
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
        return;
      }
    });
    AbilityShare.ctx = this.context
    const store = new EventStore()
    store.init(this.context).then(() => {
      AbilityShare.store = store
    })
    // 提前申请麦克风权限（录音将在页面点击时确认）
    const atManager = abilityAccessCtrl.createAtManager()
    atManager.requestPermissionsFromUser(this.context, ['ohos.permission.MICROPHONE'] as Array<Permissions>)
      .catch(() => {})
  }

  onWindowStageDestroy(): void {
  }

  onForeground(): void {
  }

  onBackground(): void {
  }
}
