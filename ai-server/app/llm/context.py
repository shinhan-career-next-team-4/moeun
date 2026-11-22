import json
import logging
from typing import Any, Literal

from anthropic.types.beta import BetaAssistantMessageParam, BetaMessageParam, BetaUserMessageParam

from app.core.contract import (
    CALCULATED_KEYS,
    EXCEEDED_KEYS,
    GOAL_REACH_MONTH,
    REQUIRED_SCENARIO_FIELDS,
    scenario_key,
)
from app.core.copy import load_copy
from app.core.errors import ErrorCode
from app.llm.assemble import note_pattern
from app.llm.errors import AgentError
from app.llm.prompts import EXPLAIN_INSTRUCTION
from app.schemas import ChatMode, ChatRequest, ChatResponse, PlanContext

# agent.py에서 옮기기 전과 같은 로거 이름을 쓴다. 운영 로그에 찍히는 이름이 바뀌지 않게 한다(#199)
log = logging.getLogger("app.llm.agent")


def _reject(req: ChatRequest, code: ErrorCode) -> AgentError:
    # planContext 본문은 로그에 남기지 않는다. 오류 코드와 calcRunId만(C1a-8)
    log.warning("plan_context rejected: code=%s calcRunId=%s", code, req.calc_run_id)
    return AgentError(code)


def _missing_display(plan: PlanContext) -> bool:
    display = plan.display
    if plan.result.status == "ALREADY_EXCEEDED":
        return any(k not in display for k in EXCEEDED_KEYS)
    required = list(CALCULATED_KEYS)
    for item in plan.result.scenarios:
        name = item.scenario.value
        required.extend(scenario_key(name, k) for k in REQUIRED_SCENARIO_FIELDS)
        if item.is_achieved:
            required.append(scenario_key(name, GOAL_REACH_MONTH))
    return any(k not in display for k in required)


def check_plan_context(req: ChatRequest) -> ChatResponse | None:
    """모델을 부르기 전 planContext 검사(계약 C1a-8 검사 2~5). 고정 문장으로 끝나면 그 응답을 돌려준다."""
    plan = req.plan_context
    if req.mode is ChatMode.EXPLAIN:
        if plan is None or plan.calc_run_id != req.calc_run_id:
            raise _reject(req, ErrorCode.BAD_REQUEST)
        if plan.result.status == "ALREADY_EXCEEDED":
            return ChatResponse(
                message_type="EXPLANATION",
                content=load_copy().get("chat.alreadyExceeded"),
                baseline_scenario=req.baseline_scenario,
                calc_run_id=req.calc_run_id,
            )
    if plan is not None and _missing_display(plan):
        raise _reject(req, ErrorCode.CONTEXT_DISPLAY_MISSING)
    return None


def plan_model_input(plan: PlanContext) -> dict[str, Any]:
    """모델에 넘기는 planContext. raw 금액·식별 정보는 빼고 display 문자열과 문구표 문장만 담는다(C1a-8)."""
    copy = load_copy()
    known = copy.keys()
    assumptions = plan.result.assumptions
    notes: list[str] = []
    dropped = 0
    for code in assumptions.excluded_items or []:
        if f"excluded.{code}" in known:
            notes.append(copy.get(f"excluded.{code}"))
        else:
            dropped += 1
    if dropped:
        log.warning("excludedItems code without copy dropped: count=%s", dropped)
    shown: dict[str, Any] = {"isDefaultSavingsRate": assumptions.is_default_savings_rate, "excludedNotes": notes}
    if assumptions.scenario_method is not None:
        shown["scenarioMethodNote"] = copy.get(f"scenarioMethod.{assumptions.scenario_method}")
    return {
        "result": {
            "status": plan.result.status,
            "scenarios": [{"scenario": s.scenario.value, "isAchieved": s.is_achieved} for s in plan.result.scenarios],
            "assumptions": shown,
        },
        "display": plan.display,
    }


def build_messages(req: ChatRequest) -> list[BetaMessageParam]:
    """기록은 user로 시작하고 역할이 번갈아야 한다. 같은 역할이 이어지면 합친다."""
    plan_input = plan_model_input(req.plan_context) if req.plan_context else None
    context = json.dumps(plan_input, ensure_ascii=False)
    body = req.text if req.mode is ChatMode.QUESTION else EXPLAIN_INSTRUCTION
    current = (
        f"<plan_context>{context}</plan_context>\n"
        f"<baseline_scenario>{req.baseline_scenario.value}</baseline_scenario>\n"
        f"<request>{body}</request>"
    )

    copy = load_copy()
    notes = (copy.get("tax.note"), copy.get("tax.noteEtfTaxFree"))
    turns: list[tuple[Literal["user", "assistant"], str]] = []
    for role, content in [*((h.role, h.content) for h in req.history), ("user", current)]:
        if role == "assistant":
            # 코드가 붙인 세금 단서는 모델 입력에 다시 넣지 않는다(D-067, D-073)
            for note in notes:
                content = note_pattern(note).sub("", content)
            content = content.strip()
            if not turns or not content:
                continue
        if turns and turns[-1][0] == role:
            turns[-1] = (role, f"{turns[-1][1]}\n\n{content}")
        else:
            turns.append((role, content))
    return [
        BetaUserMessageParam(role="user", content=content)
        if role == "user"
        else BetaAssistantMessageParam(role="assistant", content=content)
        for role, content in turns
    ]
