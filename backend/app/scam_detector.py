import json
import os

from groq import Groq
from .risk_engine import calcute_risk

MODEL = os.getenv("GROQ_LLM_MODEL","openai/gpt-oss-120b")

def detect_scam(transcript: str) -> dict:
    api_key = os.getenv("GROQ_API_KEY")
    if not api_key:
        raise RuntimeError("GROQ_API_KEY is not configured")

    client = Groq(api_key=api_key)
    prompt = f"""
you are a cautious phone-scam risk assessment assistant.
Analyze this transcript for combinations of:
- Authority impersonation
- Threats or intimidation
- Secrecy or isolation demands
- Urgent financial or UPI demands
- Requests for OTP, PIN, passwords, or remote access

Do not classify a call as a scam just because it mentions police,
money, urgency, or an official institution.

Return ONLY valid JSON with this structure:
{{
  "semantic_score": 0,
  "indicators": [],
  "explanation": "short explanation",
  "recommended_action": "CONTINUE"
}}

semantic_score must be an integer from 0 to 100.
recommended_action must be CONTINUE or WARN_USER.
Use WARN_USER for high-risk coercive patterns.

Transcript:
{transcript}
"""

    response = client.chat.completions.create(
        model=MODEL,
        messages=[
            {
                "role": "system",
                "content": "Return valid JSON only. Assess evidence conservatively."
            },
            {"role": "user", "content": prompt}
        ],
        response_format={"type": "json_object"},
        temperature=0.1
    )

    result = json.loads(response.choices[0].message.content)

    score, risk_level = calcute_risk(
        result.get("semantic_score", 0)
    )

    return {
        "semantic_score": score,
        "threat_score": score,
        "risk_level": risk_level,
        "indicators": result.get("indicators", []),
        "explanation": result.get("explanation", ""),
        "recommended_action": (
            "WARN_USER" if score > 70 else "CONTINUE"
        )
    }