def calcute_risk(sementic_score: int)-> tuple[int, str]:
    score = max(0, min(100, int(sementic_score)))

    if score < 40:
        return score, "LOW"
    elif score<= 70:
        return score, "MEDIUM"
    else:
        return score, "HIGH"