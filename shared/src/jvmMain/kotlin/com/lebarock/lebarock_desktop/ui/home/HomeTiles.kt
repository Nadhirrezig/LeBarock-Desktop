package com.lebarock.lebarock_desktop.ui.home

import com.lebarock.lebarock_desktop.data.session.Rights

/** The counter's sections (Accueil buttons). */
enum class Section(
    val title: String,
    val description: String,
    /** Orders and money go to the account's Caisse: without one the caisse refuses them (`no_caisse`). */
    val needsCaisse: Boolean,
    /** The build step that brings the section; null = there now. */
    val comesIn: String?,
) {
    POS("POS", "Prendre une commande : sections, recherche, panier, envoi en cuisine", needsCaisse = true, comesIn = null),
    TABLES("Tables / salle", "Plan de salle : ouvrir, changer de table, fusionner, séparer", needsCaisse = false, comesIn = null),
    ORDERS("Commandes", "Les commandes du jour : détail, réimpression, annulation", needsCaisse = false, comesIn = null),
    PAYMENT("Encaisser", "Espèces, carte, à crédit, partage, pourboire, remise", needsCaisse = true, comesIn = "D5"),
    CASH_REGISTER("Caisse", "Ouverture avec fond, X, Z, demande au stock, dépenses", needsCaisse = true, comesIn = "D6"),
    SETTINGS("Réglages", "Serveur, certificat, session, application", needsCaisse = false, comesIn = null),
    ;

    val available: Boolean get() = comesIn == null
}

object HomeTiles {
    /**
     * The buttons this account sees, in Accueil order (client, 2026-11-08: "shown by the account's
     * rights"). A kitchen account only gets Réglages: its screen is the 22" kitchen screen's app.
     */
    fun of(rights: Rights): List<Section> = buildList {
        if (!rights.kitchenOnly) {
            if (rights.orders) add(Section.POS)
            if (rights.orders) add(Section.TABLES)
            if (rights.showOrders) add(Section.ORDERS)
            if (rights.takePayment) add(Section.PAYMENT)
            if (rights.cashRegister) add(Section.CASH_REGISTER)
        }
        add(Section.SETTINGS)
    }
}
