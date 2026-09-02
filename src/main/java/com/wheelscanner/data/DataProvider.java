package com.wheelscanner.data;

import com.wheelscanner.model.Opportunity;

import java.util.List;

/**
 * Interface permettant de changer facilement de source de données
 * (Yahoo → IBKR) sans modifier le reste du code.
 */
public interface DataProvider {

    /**
     * Recherche les opportunités (Wheel CSP + PMCC) pour un ticker donné.
     *
     * @param ticker symbole de l'action (ex: "F", "NIO")
     * @return liste d'opportunités trouvées (peut être vide)
     */
    List<Opportunity> findOpportunities(String ticker);
}
