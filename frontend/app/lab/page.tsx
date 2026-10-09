import { CapsPanel } from "@/components/lab/CapsPanel";
import { ResetPanel } from "@/components/lab/ResetPanel";
import { ScenarioCatalog } from "@/components/lab/ScenarioCatalog";
import { StateMachineView } from "@/components/lab/StateMachineView";
import { SystemMap } from "@/components/lab/SystemMap";
import { Card, Notice, PageHeader } from "@/components/ui";

export default function LabHome() {
  return (
    <div className="space-y-5">
      <PageHeader
        title="The Lab"
        subtitle="Run the real settlement engine in a sandbox and watch it work. You are not watching a recording: every button here calls the same code the staging deployment runs."
      />
      <Notice tone="blue" title="Where faults are injected.">
        Only at the engine's boundaries: the gateway call, the external record store, the database row a human would edit, and a crash point
        between the two transactions. Nothing is changed inside the engine's own logic: it sees exactly what a flaky external system or a
        careless human would show it. Six known gaps in the engine are shown as they are, each labelled and linked to the doc that records it.
      </Notice>
      <SystemMap />
      <StateMachineView />
      <CapsPanel />
      <ResetPanel />
      <Card title="Scenario catalog">
        <ScenarioCatalog />
      </Card>
    </div>
  );
}
